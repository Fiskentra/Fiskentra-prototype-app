#requires -Version 7.0
<#
Twenty real Map -> Saved -> Map navigation cycles, without creating/editing user data.
Start with Fiskentra already foreground on Map, overlays closed and performance fixtures off.
Only navigation taps are sent. No launch, force-stop, permission/network, GC or settings change.
Typical duration is about two minutes; slow/disconnected devices fail within MaxSeconds.
Memory methodology: https://developer.android.com/topic/performance/memory/guide/tools-overview
The Objects/Activities count and PSS are observations, not a retained-object heap proof.
#>
param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateRange(1,20)][int]$Cycles=20,
    [ValidateRange(60,300)][int]$MaxSeconds=240,
    [ValidateRange(0,6000)][int]$MinimumCycleMs=5000,
    [string]$OutputDirectory,
    [string]$Adb='C:\Users\Gebruiker\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if(-not $OutputDirectory){$OutputDirectory=Join-Path $repo ('qa\map-lifecycle\'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$package='com.fiskentra.app'
$uiPath='/sdcard/fiskentra-map-lifecycle-'+[guid]::NewGuid().ToString('N')+'.xml'
$runClock=[Diagnostics.Stopwatch]::StartNew()
$samples=[Collections.Generic.List[object]]::new()
$transitions=[Collections.Generic.List[object]]::new()
$firstGuard=$null;$lastGuard=$null;$originalState=$null;$finalState=$null
$completedCycles=0;$initialPid=$null;$failure=$null;$cleanup='NOT NEEDED';$uiCreated=$false

function Adb-Checked([string[]]$Arguments,[int]$TimeoutMs=15000,[switch]$CleanupCall){
    $remaining=[int](($MaxSeconds-$runClock.Elapsed.TotalSeconds)*1000)
    if(-not $CleanupCall){
        if($remaining -le 0){throw 'Lifecycle runner time limit reached'}
        $TimeoutMs=[Math]::Min($TimeoutMs,$remaining)
    }
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$Adb;$start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($argument in (@('-s',$Serial)+$Arguments)){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    try{
        $null=$process.Start()
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit($TimeoutMs)){
            # Terminate only this runner's own ADB client, never the app or shared ADB server.
            $process.Kill();$process.WaitForExit();throw 'ADB command exceeded the bounded timeout'
        }
        $value=$stdout.GetAwaiter().GetResult();$errorText=$stderr.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0){throw ('ADB command failed: '+(($Arguments | Select-Object -First 3) -join ' '))}
        if($errorText -match 'device offline|device unauthorized|device .* not found'){throw 'ADB device unavailable'}
        return $value
    } finally {$process.Dispose()}
}
function Assert-Foreground {
    $window=Adb-Checked @('shell','dumpsys','window')
    $focus=@($window -split "`n" | Where-Object {$_ -match '\bmCurrentFocus='})
    $own=$focus.Count -eq 1 -and $focus[0] -match 'com\.fiskentra\.app/'
    # Never persist another app's title/package or the complete window dump.
    $entry=[pscustomobject]@{utc=[DateTimeOffset]::UtcNow.ToString('o');elapsed_seconds=[Math]::Round($runClock.Elapsed.TotalSeconds,3);own_app_foreground=[bool]$own;source='dumpsys window mCurrentFocus'}
    if($null -eq $script:firstGuard){$script:firstGuard=$entry}
    $script:lastGuard=$entry
    if(-not $own){throw 'Fiskentra lost foreground; stopped before sending another input'}
}
function Read-Ui {
    Assert-Foreground
    $script:uiCreated=$true
    $dump=Adb-Checked @('shell','uiautomator','dump',$uiPath)
    if($dump -notmatch 'dumped to:' -or $dump -match 'ERROR:'){throw 'Fresh UI dump failed'}
    [xml]$xml=Adb-Checked @('shell','cat',$uiPath)
    Assert-Foreground
    return $xml
}
function Read-Bounds($Node){
    if($Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'Invalid UI bounds'}
    $bounds=@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4])
    if($bounds[2] -le $bounds[0] -or $bounds[3] -le $bounds[1]){throw 'Empty UI bounds'}
    return ,$bounds
}
function Read-UiState([xml]$Xml){
    $nodes=@($Xml.SelectNodes('//node') | Where-Object {$_.package -eq $package})
    $allBounds=@($nodes | ForEach-Object {if($_.bounds -match '^\[\d+,\d+\]\[\d+,(\d+)\]$'){[int]$Matches[1]}})
    if($allBounds.Count -eq 0){throw 'No Fiskentra UI nodes'}
    $bottom=($allBounds | Measure-Object -Maximum).Maximum
    $nav=@{}
    foreach($label in @('Map','Journal','Saved','Devices')){
        $found=@($nodes | Where-Object {$_.'content-desc' -eq $label -and $_.clickable -eq 'true' -and $_.enabled -eq 'true'})
        if($found.Count -ne 1){throw ('Expected one enabled navigation tab: '+$label)}
        $bounds=Read-Bounds $found[0]
        if($bounds[1] -lt $bottom*.70){throw 'Navigation tab is not in the bottom navigation bar'}
        $nav[$label]=$bounds
    }
    $mapCount=@($nodes | Where-Object {$_.'content-desc' -like '*MapLibre*'}).Count
    $searchCount=@($nodes | Where-Object {$_.class -eq 'android.widget.EditText' -and ($_.text -eq 'Search saved items' -or $_.hint -eq 'Search saved items')}).Count
    $savedHeading=@($nodes | Where-Object {$_.text -eq 'Saved' -and (Read-Bounds $_)[1] -lt $bottom*.70}).Count
    $overlay=@($nodes | Where-Object {$_.'content-desc' -in @('Expand panel','Collapse panel')}).Count -gt 0
    $route=if($mapCount -eq 1){'map'}elseif($mapCount -eq 0 -and $savedHeading -ge 1 -and $searchCount -eq 1){'saved'}else{'unknown'}
    return [pscustomobject]@{route=$route;map_surfaces=$mapCount;overlay_open=$overlay;navigation=$nav}
}
function Wait-Route([string]$Expected,[int]$Attempts=3){
    for($attempt=0;$attempt -lt $Attempts;$attempt++){
        $state=Read-UiState (Read-Ui)
        if($state.route -eq $Expected -and -not $state.overlay_open){return $state}
        if($attempt+1 -lt $Attempts){Start-Sleep -Milliseconds 250}
    }
    throw ('Expected unobscured route: '+$Expected)
}
function Tap-Tab($State,[string]$Label,[string]$Expected,[int]$Cycle){
    $bounds=$State.navigation[$Label]
    $x=[int](($bounds[0]+$bounds[2])/2);$y=[int](($bounds[1]+$bounds[3])/2)
    Assert-Foreground
    $tapTime=[Diagnostics.Stopwatch]::StartNew()
    $null=Adb-Checked @('shell','input','tap',"$x","$y")
    $next=Wait-Route $Expected
    $transitions.Add([pscustomobject]@{cycle=$Cycle;from=$State.route;to=$Expected;ui_verified=$true;elapsed_ms=[Math]::Round($tapTime.Elapsed.TotalMilliseconds);note='Includes ADB and UIAutomator latency; not an app action-latency measurement'})
    return $next
}
function Match-Number([string]$Text,[string]$Pattern){
    $match=[regex]::Match($Text,$Pattern)
    if($match.Success){return [long]$match.Groups[1].Value}
    return $null
}
function Heap-Allocation([string]$Text,[string]$Kind){
    $row=[regex]::Match($Text,'(?m)^[ \t]*'+$Kind+' Heap[ \t]+([\d \t]+)\r?$')
    if(-not $row.Success){return $null}
    $columns=@([regex]::Matches($row.Groups[1].Value,'\d+') | ForEach-Object {[long]$_.Value})
    # Heap Size / Alloc / Free are the final three columns of the detailed heap rows.
    if($columns.Count -ge 7){return $columns[-2]}
    return $null
}
function Capture-Memory([string]$Label,[int]$Cycle){
    Assert-Foreground
    Start-Sleep -Milliseconds 1500
    Assert-Foreground
    $appPid=(Adb-Checked @('shell','pidof',$package)).Trim()
    if($appPid -notmatch '^\d+$' -or $appPid -ne $initialPid){throw 'App PID changed; lifecycle comparison is not continuous'}
    $memory=Adb-Checked @('shell','dumpsys','meminfo',$package)
    if($memory -notmatch ('\*\* MEMINFO in pid '+$appPid+' \['+[regex]::Escape($package)+'\] \*\*')){throw 'Memory report does not match the app process'}
    Set-Content -LiteralPath (Join-Path $OutputDirectory ($Label+'-meminfo.txt')) -Value $memory -Encoding utf8
    $samples.Add([pscustomobject]@{
        label=$Label;completed_cycles=$Cycle;elapsed_seconds=[Math]::Round($runClock.Elapsed.TotalSeconds,3);pid=$appPid
        activities=(Match-Number $memory '\bActivities:\s+(\d+)');view_roots=(Match-Number $memory '\bViewRootImpl:\s+(\d+)');views=(Match-Number $memory '\bViews:\s+(\d+)')
        total_pss_kib=(Match-Number $memory '\bTOTAL PSS:\s+(\d+)');total_rss_kib=(Match-Number $memory '\bTOTAL RSS:\s+(\d+)')
        java_heap_alloc_kib=(Heap-Allocation $memory 'Dalvik');native_heap_alloc_kib=(Heap-Allocation $memory 'Native')
        gc='NOT REQUESTED: no explicit app debug GC hook';post_gc_retained_objects='NOT RUN'
    })
}
try {
    Assert-Foreground
    $state=Wait-Route 'map' 1
    $originalState=[pscustomobject]@{route=$state.route;map_surfaces=$state.map_surfaces;overlay_open=$state.overlay_open;keep_awake_changed=$false;fixtures_changed=$false;network_changed=$false}
    $initialPid=(Adb-Checked @('shell','pidof',$package)).Trim()
    if($initialPid -notmatch '^\d+$'){throw 'Expected exactly one Fiskentra app process'}
    Capture-Memory 'start' 0
    for($cycle=1;$cycle -le $Cycles;$cycle++){
        $cycleClock=[Diagnostics.Stopwatch]::StartNew()
        # Coordinates always come from the latest verified UI, never from a fixed screen position.
        $state=Tap-Tab $state 'Saved' 'saved' $cycle
        $state=Tap-Tab $state 'Map' 'map' $cycle
        $completedCycles=$cycle
        while($cycleClock.ElapsedMilliseconds -lt $MinimumCycleMs){Start-Sleep -Milliseconds 250;Assert-Foreground}
        if($cycle -eq [Math]::Ceiling($Cycles/2)){Capture-Memory 'middle' $cycle}
        if($cycle%5 -eq 0){Write-Output ('Lifecycle: '+$cycle+'/'+$Cycles+' Map -> Saved -> Map cycles verified')}
    }
    Capture-Memory 'end' $completedCycles
    $state=Wait-Route 'map' 1
    $finalState=[pscustomobject]@{route=$state.route;map_surfaces=$state.map_surfaces;overlay_open=$state.overlay_open}
    Assert-Foreground
} catch {
    $failure=$_.Exception.Message
    Write-Warning $failure
} finally {
    # No recovery tap or launch: leave a user's changed foreground/state alone on interruption.
    # This exact random QA XML is the only device file the runner creates; it is not app/user storage.
    if($uiCreated){
        try{$null=Adb-Checked @('shell','rm','-f',$uiPath) 3000 -CleanupCall;$cleanup='Removed own temporary UI dump'}
        catch{$cleanup='Temporary QA UI dump could not be removed (ADB unavailable)'}
    }
    $complete=$null -eq $failure -and $completedCycles -eq $Cycles
    $report=[ordered]@{
        status=$(if($complete){'PASS navigation observations only'}else{'INCOMPLETE'})
        device_serial=$Serial;utc=[DateTimeOffset]::UtcNow.ToString('o');elapsed_seconds=[Math]::Round($runClock.Elapsed.TotalSeconds,3)
        requested_cycles=$Cycles;completed_cycles=$completedCycles;max_seconds=$MaxSeconds
        original_state=$originalState;final_state=$finalState;first_own_ui_guard=$firstGuard;last_own_ui_guard=$lastGuard
        snapshots=$samples.ToArray();transitions=$transitions.ToArray();failure=$failure;temporary_ui_dump_cleanup=$cleanup
        retained_memory_proof='NOT RUN: no explicit GC confirmation, heap graph or retained-object analysis'
        activity_count_scope='dumpsys meminfo Objects/Activities count in the same PID; no Activity recreation is claimed'
        user_data_scope='Only bottom navigation tabs were tapped; no point/trip/photo/sync actions or private stores read/written by the runner. Existing recording/sync services continue normally.'
        original_state_scope='Started with unobscured Map and ends on Map on success. No settings/fixture changes; camera/filter identity is not independently sampled.'
        memory_reference='https://developer.android.com/topic/performance/memory/guide/tools-overview'
    }
    $report | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'report.json') -Encoding utf8
}
if($null -ne $failure){exit 1}
Write-Output ('Lifecycle report: '+(Join-Path $OutputDirectory 'report.json'))
