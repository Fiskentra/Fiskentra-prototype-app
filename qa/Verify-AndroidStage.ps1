param([Parameter(Mandatory=$true)][string]$Serial,[string[]]$Only,[switch]$LowerSections)
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb -s $Serial shell input keyevent 224
$cases=@(
    @{Name='device';Activity='MainActivity';Extras='--ez qa_keep_awake true --es qa_route device';Expected='Flic 2 button mapping'},
    @{Name='forecastDetail';Activity='DesignPreviewActivity';Extras='--es screen forecastDetail';Expected='Bite activity (5 days)'},
    @{Name='tripSummary';Activity='DesignPreviewActivity';Extras='--es screen tripSummary';Expected='Catches (5)'},
    @{Name='profileSetup';Activity='DesignPreviewActivity';Extras='--es screen profileSetup';Expected='Set up your profile'}
)
if($Only){$cases=@($cases | Where-Object {$_.Name -in $Only})}
foreach($case in $cases){
    Write-Output ('Inspecting '+$case.Name)
    $command='am start -f 0x04000000 -n com.fiskentra.app/.'+$case.Activity+' '+$case.Extras+' && uiautomator dump /sdcard/fiskentra-stage.xml && screencap -p /sdcard/fiskentra-stage.png'
    $capture=& $adb -s $Serial shell $command 2>&1
    $capture | Write-Output
    for($attempt=0;$attempt -lt 2 -and -not ($capture -match 'UI hierchary dumped');$attempt++){
        $capture=& $adb -s $Serial shell 'uiautomator dump /sdcard/fiskentra-stage.xml && screencap -p /sdcard/fiskentra-stage.png' 2>&1
        $capture | Write-Output
    }
    if($LASTEXITCODE -ne 0 -or -not ($capture -match 'UI hierchary dumped')){throw ('No current UI tree: '+$case.Name+'. Unlock the phone before retrying.')}
    $xmlPath=Join-Path $PSScriptRoot ('final-'+$case.Name+'.xml')
    & $adb -s $Serial pull /sdcard/fiskentra-stage.xml $xmlPath
    if($LASTEXITCODE -ne 0){throw 'UI tree download failed'}
    $ui=Get-Content -LiteralPath $xmlPath -Raw
    if(-not $ui.Contains($case.Expected)){throw ('Wrong or obstructed route: '+$case.Name)}
    if($case.Name -eq 'device' -and $ui -match 'Watch|Wear OS'){throw 'Watch UI remains'}
    & $adb -s $Serial pull /sdcard/fiskentra-stage.png (Join-Path $PSScriptRoot ('final-'+$case.Name+'.png'))
    if($LASTEXITCODE -ne 0){throw 'Screenshot download failed'}
    Write-Output ('Verified '+$case.Name)
    if($LowerSections -and $case.Name -ne 'device'){
        [xml]$tree=$ui
        if($case.Name -eq 'profileSetup'){
            $choice=$tree.SelectNodes('//node') | Where-Object {$_.text -eq 'Imperial'} | Select-Object -First 1
            if(-not $choice){throw 'Imperial selector is not visible'}
            $rect=[regex]::Matches($choice.bounds,'\d+') | ForEach-Object {[int]$_.Value}
            & $adb -s $Serial shell input tap ([int](($rect[0]+$rect[2])/2)) ([int](($rect[1]+$rect[3])/2))
        }
        $scroll=$tree.SelectNodes('//node') | Where-Object {$_.scrollable -eq 'true' -and $_.class -eq 'android.widget.ScrollView'} | Select-Object -First 1
        if(-not $scroll){throw 'Vertical scroll surface unavailable'}
        $rect=[regex]::Matches($scroll.bounds,'\d+') | ForEach-Object {[int]$_.Value}
        $x=[int](($rect[0]+$rect[2])/2);$from=$rect[3]-90;$to=$rect[1]+90
        & $adb -s $Serial shell "input swipe $x $from $x $to 450 && input swipe $x $from $x $to 450 && uiautomator dump /sdcard/fiskentra-lower.xml && screencap -p /sdcard/fiskentra-lower.png"
        if($LASTEXITCODE -ne 0){throw 'Lower section capture failed'}
        $lowerPath=Join-Path $PSScriptRoot ('v0162-lower-'+$case.Name+'.xml')
        & $adb -s $Serial pull /sdcard/fiskentra-lower.xml $lowerPath
        $lower=Get-Content -LiteralPath $lowerPath -Raw
        $expected=switch($case.Name){'profileSetup' {'CONTINUE'} 'tripSummary' {'EXPORT GPX'} 'forecastDetail' {'DATA SOURCE'}}
        if(-not $lower.Contains($expected)){throw ('Bottom control missing: '+$case.Name)}
        & $adb -s $Serial pull /sdcard/fiskentra-lower.png (Join-Path $PSScriptRoot ('v0162-lower-'+$case.Name+'.png'))
        if($LASTEXITCODE -ne 0){throw 'Lower screenshot download failed'}
        Write-Output ('Verified lower '+$case.Name)
    }
}
& $adb -s $Serial shell am start -f 0x04000000 -n com.fiskentra.app/.MainActivity --ez qa_keep_awake false --es qa_route home
if($LASTEXITCODE -ne 0){throw 'Could not restore normal app'}
