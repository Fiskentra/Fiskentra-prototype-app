param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('standard','stress')][string[]]$Datasets=@('standard','stress'),
    [ValidateRange(60,180)][int]$Seconds=60,
    [string]$OutputDirectory,
    [string]$Adb='C:\Users\Gebruiker\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if(-not $OutputDirectory){$OutputDirectory=Join-Path $repo ('qa\map-performance\'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$package='com.fiskentra.app'
function Adb-Checked([string[]]$Arguments){
    $value=(& $Adb -s $Serial @Arguments 2>&1 | Out-String)
    if($LASTEXITCODE -ne 0){throw ('ADB failed: '+($Arguments -join ' ')+"`n"+$value)}
    return $value
}
function Assert-Foreground {
    $focusWait=[Diagnostics.Stopwatch]::StartNew()
    while($true){
        # ColorOS exposes current focus in the complete window dump, not consistently in "window windows".
        $window=Adb-Checked @('shell','dumpsys','window')
        $focus=@($window -split "`n" | Where-Object {$_ -match '\bmCurrentFocus='})
        if($focus.Count -eq 1 -and $focus[0] -match 'com\.fiskentra\.app/'){return}
        $nonNull=@($focus | Where-Object {$_ -notmatch '\bmCurrentFocus=\s*null\s*$'})
        # Another app/dialog gets an immediate stop. Only absence of focus during a window transition may settle.
        if($nonNull.Count -gt 0 -or $focusWait.ElapsedMilliseconds -ge 3000){throw ('Fiskentra is not foreground; stopping benchmark. Focus: '+($focus -join ' '))}
        $remaining=3000-$focusWait.ElapsedMilliseconds
        Start-Sleep -Milliseconds ([Math]::Min(250,[Math]::Max(1,$remaining)))
    }
}
function Debug-Intent([string[]]$Extra){
    Assert-Foreground
    $null=Adb-Checked (@('shell','am','start','-n','com.fiskentra.app/.MainActivity')+$Extra)
    Assert-Foreground
}
function Map-Bounds {
    Assert-Foreground
    $dump=Adb-Checked @('shell','uiautomator','dump','/sdcard/fiskentra-map-performance.xml')
    if($dump -notmatch 'dumped to:' -or $dump -match 'ERROR:'){throw 'Fresh UI dump failed'}
    [xml]$xml=Adb-Checked @('shell','cat','/sdcard/fiskentra-map-performance.xml')
    $map=@($xml.SelectNodes('//node') | Where-Object {$_.package -eq $package -and $_.'content-desc' -like '*MapLibre*'})
    if($map.Count -ne 1){throw 'Expected one visible MapLibre surface'}
    Assert-Foreground
    return @([regex]::Matches($map[0].bounds,'\d+') | ForEach-Object {[int]$_.Value})
}
$reports=@()
$runStarted=[DateTimeOffset]::UtcNow.ToString('o')
$runId=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stage='initial process';$dataset=$null;$runFailure=$null;$cleanup='NOT ATTEMPTED'
$commit=(& git -C $repo rev-parse HEAD | Out-String).Trim()
$dirty=(& git -C $repo status --porcelain | Out-String).Trim().Length -gt 0
$initialPid=$null
try {
    Assert-Foreground
    $initialPid=(Adb-Checked @('shell','pidof',$package)).Trim()
    foreach($dataset in $Datasets){
        # Require a fresh native map before changing fixtures. Do not navigate out of an unrelated app screen.
        $stage='verify map before fixture'
        $null=Map-Bounds
        $stage='activate fixture'
        Debug-Intent @('--es','qa_route','map','--es','qa_map_dataset',$dataset,'--ez','qa_keep_awake','true')
        # Native style/fixtures are asynchronous. Warm up before measuring, without waiting for arbitrary tile completeness.
        for($warm=0;$warm -lt 6;$warm++){Start-Sleep -Seconds 1;Assert-Foreground}
        $bounds=Map-Bounds
        $width=$bounds[2]-$bounds[0];$height=$bounds[3]-$bounds[1]
        $x1=[int]($bounds[0]+$width*.35);$x2=[int]($bounds[0]+$width*.65)
        $y1=[int]($bounds[1]+$height*.43);$y2=[int]($bounds[1]+$height*.50)
        $pidBefore=(Adb-Checked @('shell','pidof',$package)).Trim()
        $null=Adb-Checked @('shell','dumpsys','gfxinfo',$package,'reset')
        $label=$dataset+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
        $requestedAt=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $stage='start profile'
        Debug-Intent @('--es','qa_map_profile','start','--es','qa_map_profile_label',$label)
        $clock=[Diagnostics.Stopwatch]::StartNew();$gestures=0;$zooms=0
        $stage='pan and zoom'
        while($clock.Elapsed.TotalSeconds -lt $Seconds){
            Assert-Foreground
            if($gestures%2 -eq 0){$null=Adb-Checked @('shell','input','swipe',"$x1","$y1","$x2","$y2",'400')}
            else{$null=Adb-Checked @('shell','input','swipe',"$x2","$y2","$x1","$y1",'400')}
            $gestures++
            if($gestures%4 -eq 0){
                $delta=if($zooms%4 -lt 2){'0.5'}else{'-0.5'}
                Debug-Intent @('--ef','qa_map_zoom',$delta)
                $zooms++
            }
        }
        $stage='stop profile'
        Debug-Intent @('--es','qa_map_profile','stop')
        $stage='collect profile'
        $profile=$null;$profileText=''
        for($attempt=0;$attempt -lt 12;$attempt++){
            Start-Sleep -Milliseconds 500
            $profileText=Adb-Checked @('shell','run-as',$package,'cat','files/qa/map-profile.json')
            try{$candidate=$profileText | ConvertFrom-Json;if($candidate.label -eq $label){$profile=$candidate;break}}catch{}
        }
        if($null -eq $profile){throw 'No fresh profile; verify MainActivity debug hooks are installed'}
        Set-Content -LiteralPath (Join-Path $OutputDirectory ($dataset+'-frames.json')) -Value $profileText -Encoding utf8
        Set-Content -LiteralPath (Join-Path $OutputDirectory ($dataset+'-frames.csv')) -Value (Adb-Checked @('shell','run-as',$package,'cat','files/qa/map-profile.csv')) -Encoding utf8
        Set-Content -LiteralPath (Join-Path $OutputDirectory ($dataset+'-gfxinfo.txt')) -Value (Adb-Checked @('shell','dumpsys','gfxinfo',$package,'framestats')) -Encoding utf8
        Set-Content -LiteralPath (Join-Path $OutputDirectory ($dataset+'-meminfo.txt')) -Value (Adb-Checked @('shell','dumpsys','meminfo',$package)) -Encoding utf8
        Set-Content -LiteralPath (Join-Path $OutputDirectory ($dataset+'-exit-info.txt')) -Value (Adb-Checked @('shell','dumpsys','activity','exit-info',$package)) -Encoding utf8
        $pidAfter=(Adb-Checked @('shell','pidof',$package)).Trim()
        $runtimeLog=Adb-Checked @('logcat','-d','-v','brief','--pid',$pidAfter,'-s','AndroidRuntime:E','FiskentraMapQA:I')
        Set-Content -LiteralPath (Join-Path $OutputDirectory ($dataset+'-runtime.txt')) -Value $runtimeLog -Encoding utf8
        $result=[ordered]@{dataset=$dataset;commit=$commit;dirty_worktree=$dirty;serial=$Serial;requested_seconds=$Seconds;measured_seconds=$profile.duration_seconds;pan_gestures=$gestures;debug_camera_zooms=$zooms;process_survived=($pidAfter -eq $pidBefore);frames=$profile;local_action_latency='NOT RUN';retained_memory_lifecycle='NOT RUN';battery_comparison='NOT RUN';notes='Pan uses touchscreen swipes; zoom uses native-camera debug intents to avoid opening dense markers. Android Window frame metrics do not independently prove compositor smoothness or absence of ANR. Review runtime/exit-info logs. No point/trip stores are written.'}
        $reports+=,$result
        $result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutputDirectory ($dataset+'-report.json')) -Encoding utf8
        Write-Output ("{0}: p95 {1} ms, >50ms {2}%, {3} Hz, {4} samples; frame budget {5}" -f $dataset,$profile.p95_ms,$profile.over_50ms_percent,$profile.refresh_hz,$profile.sample_count,$profile.standard_60hz_frame_budget)
    }
    $stage='complete'
} catch {
    $runFailure=[ordered]@{utc=[DateTimeOffset]::UtcNow.ToString('o');run_started=$runStarted;stage=$stage;dataset=$dataset;message=$_.Exception.Message;initial_pid=$initialPid;completed_profiles=$reports.Count;status='ABORTED; no PASS inferred for the interrupted dataset'}
    $runFailure | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory ('run-error-'+$runId+'.json')) -Encoding utf8
    # Read only this app's original PID diagnostics. A foreground failure can be a real crash; do not relaunch it here.
    if($initialPid -match '^\d+$'){
        try{
            $runtime=Adb-Checked @('logcat','-d','-v','brief','--pid',$initialPid,'-s','AndroidRuntime:E','FiskentraMapQA:I')
            Set-Content -LiteralPath (Join-Path $OutputDirectory ('abort-runtime-'+$runId+'.txt')) -Value $runtime -Encoding utf8
        }catch{Write-Warning 'Could not collect the interrupted app process diagnostics'}
    }
    throw
} finally {
    # Never pull the user back from another app merely to restore QA controls.
    try{Assert-Foreground;Debug-Intent @('--es','qa_map_profile','stop','--es','qa_map_dataset','off','--ez','qa_keep_awake','false');$cleanup='QA controls restored while own app was foreground'}catch{$cleanup='NOT CONFIRMED: foreground lost or ADB unavailable';Write-Warning 'QA cleanup was not confirmed because Fiskentra lost foreground or ADB disconnected. Profiler must also stop in the activity lifecycle.'}
    $reports | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding utf8
    [ordered]@{run_started=$runStarted;run_id=$runId;status=$(if($null -eq $runFailure){'COMPLETED'}else{'ABORTED'});last_stage=$stage;completed_profiles=$reports.Count;cleanup=$cleanup;failure=$runFailure} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory ('run-status-'+$runId+'.json')) -Encoding utf8
}
