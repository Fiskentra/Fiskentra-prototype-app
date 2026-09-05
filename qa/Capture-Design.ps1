param([ValidateSet('core','auth','setup','details')][string]$Group='core',[string]$Serial=$env:ANDROID_SERIAL,[string[]]$Only)
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$adbArgs=@()
if($Serial){$adbArgs=@('-s',$Serial)}
& $adb @adbArgs shell input keyevent 224
if($Group -eq 'core'){$screens=@('home','map','saved','log','device','mapTools')}
elseif($Group -eq 'auth'){$screens=@('onboarding','signin','signup','recover','checkEmail','resetSent')}
elseif($Group -eq 'setup'){$screens=@('profileSetup','flicRequired','step0','step1','step2','step3')}
else{$screens=@('catchEdit','tripSetup','forecastDetail','species','tripActive','tripSummary')}
if($Only){$screens=@($screens | Where-Object {$_ -in $Only});if($screens.Count -eq 0){throw 'No matching screens in selected group'}}
foreach($screen in $screens){
    if($Group -eq 'core') { & $adb @adbArgs shell am start -n com.fiskentra.app/.MainActivity --ez qa_keep_awake true --es qa_route $screen }
    else {
        $route=$screen
        $mode='signin'
        $step=0
        if($screen -in @('signin','signup','recover','checkEmail','resetSent')){$route='profile';$mode=$screen}
        if($screen.StartsWith('step')){$route='flicSetup';$step=[int]$screen.Substring(4)}
        & $adb @adbArgs shell am start -f 0x04000000 -n com.fiskentra.app/.DesignPreviewActivity --es screen $route --es mode $mode --ei step $step
    }
    $uiReady=$false
    for($attempt=0;$attempt -lt 3;$attempt++){
        $dump = & $adb @adbArgs shell uiautomator dump /sdcard/fiskentra-qa-ui.xml 2>&1
        if($LASTEXITCODE -eq 0 -and ($dump -match 'UI hierchary dumped')){$uiReady=$true;break}
        Start-Sleep -Milliseconds 300
    }
    if(-not $uiReady){throw "No current UI tree: $screen. Capture stopped; existing proof is not overwritten."}
    $focus = & $adb @adbArgs shell 'dumpsys window | grep mCurrentFocus'
    if(-not ($focus -match 'mCurrentFocus=.*com.fiskentra.app/')){throw "Phone is locked or another screen covers Fiskentra: $screen"}
    & $adb @adbArgs pull /sdcard/fiskentra-qa-ui.xml (Join-Path $PSScriptRoot "final-$screen.xml")
    & $adb @adbArgs shell screencap -p /sdcard/fiskentra-qa.png
    & $adb @adbArgs pull /sdcard/fiskentra-qa.png (Join-Path $PSScriptRoot "final-$screen.png")
    if($LASTEXITCODE -ne 0){throw "Screenshot failed: $screen"}
}
& $adb @adbArgs shell am start -f 0x04000000 -n com.fiskentra.app/.MainActivity --ez qa_keep_awake true --es qa_route home
