param([Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$Serial)

$ErrorActionPreference='Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'scripts\Android-Environment.ps1')
$adb=(Resolve-FiskentraAdb '')
$evidence=Join-Path $PSScriptRoot 'layers-v028'
function Invoke-Phone { param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
    $result=& $adb -s $serial @Arguments
    if($LASTEXITCODE -ne 0){throw ('ADB failed: '+($Arguments -join ' '))}
    return $result
}
$wifi=(Invoke-Phone @('shell','settings','get','global','wifi_on')).Trim()
$mobile=(Invoke-Phone @('shell','settings','get','global','mobile_data')).Trim()
if($wifi -notin @('0','1') -or $mobile -notin @('0','1')){throw 'Unknown original network state'}
$original=(Invoke-Phone @('exec-out','run-as','com.fiskentra.app','cat','shared_prefs/fiskentra_weather_cache.xml')) -join "`n"
[xml]$validated=$original
if(-not $validated.SelectSingleNode('/map/string[@name="weather"]')){throw 'No weather cache to exercise'}
$backup=Join-Path $evidence 'weather-before-offline.xml'
[IO.File]::WriteAllText($backup,$original,[Text.UTF8Encoding]::new($false))
try {
    Invoke-Phone @('shell','svc','wifi','disable') | Out-Null
    Invoke-Phone @('shell','svc','data','disable') | Out-Null
    foreach($ageMinutes in @(35,61)) {
        Invoke-Phone @('shell','am','force-stop','com.fiskentra.app') | Out-Null
        [xml]$aged=$original
        $record=$aged.SelectSingleNode('/map/string[@name="weather"]').InnerText | ConvertFrom-Json
        $record.observed_at=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()-$ageMinutes*60000L
        $aged.SelectSingleNode('/map/string[@name="weather"]').InnerText=($record | ConvertTo-Json -Compress -Depth 30)
        $aged.SelectSingleNode('/map/long[@name="fetched_at"]').SetAttribute('value',[string]$record.observed_at)
        $testFile=Join-Path $evidence ('weather-aged-'+$ageMinutes+'.xml')
        $aged.Save($testFile)
        Invoke-Phone @('push',$testFile,'/data/local/tmp/fiskentra-wind-test.xml') | Out-Null
        Invoke-Phone @('shell','run-as','com.fiskentra.app','cp','/data/local/tmp/fiskentra-wind-test.xml','shared_prefs/fiskentra_weather_cache.xml') | Out-Null
        Invoke-Phone @('shell','am','start','-n','com.fiskentra.app/.MainActivity','--es','qa_route','map','--es','qa_map_dataset','layers','--ez','qa_keep_awake','true') | Out-Null
        Start-Sleep -Seconds 5
        Invoke-Phone @('shell','screencap','-p','/sdcard/fiskentra-wind-offline.png') | Out-Null
        Invoke-Phone @('pull','/sdcard/fiskentra-wind-offline.png',(Join-Path $evidence ('wind-offline-'+$ageMinutes+'min.png'))) | Out-Null
        Write-Output ('Captured offline restart with cache age '+$ageMinutes+' minutes')
    }
} finally {
    Invoke-Phone @('shell','am','force-stop','com.fiskentra.app') | Out-Null
    Invoke-Phone @('push',$backup,'/data/local/tmp/fiskentra-wind-test.xml') | Out-Null
    Invoke-Phone @('shell','run-as','com.fiskentra.app','cp','/data/local/tmp/fiskentra-wind-test.xml','shared_prefs/fiskentra_weather_cache.xml') | Out-Null
    $wifiAction=if($wifi -eq '1'){'enable'}else{'disable'}
    $mobileAction=if($mobile -eq '1'){'enable'}else{'disable'}
    Invoke-Phone @('shell','svc','wifi',$wifiAction) | Out-Null
    Invoke-Phone @('shell','svc','data',$mobileAction) | Out-Null
    Invoke-Phone @('shell','am','start','-n','com.fiskentra.app/.MainActivity','--es','qa_route','map','--es','qa_map_dataset','layers','--ez','qa_keep_awake','true') | Out-Null
    Write-Output 'Original weather cache and network state restored'
}
