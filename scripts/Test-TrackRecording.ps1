param(
    [string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11',
    [string]$AndroidJar='C:\Users\Gebruiker\AppData\Local\Android\Sdk\platforms\android-35\android.jar'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\track-recording-tests'
$json=Join-Path $repo 'build\test-deps\json-20240303.jar'
if(-not (Test-Path -LiteralPath $json)){throw 'Run Test-RoutingParser.ps1 to prepare the shared JSON test dependency'}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source=Join-Path $repo 'app\src\main\java\com\fiskentra\app'
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp "$json;$AndroidJar" -d $output (Join-Path $source 'data\TrackStore.java') (Join-Path $source 'model\TrackPointPolicy.java') (Join-Path $source 'model\WeatherSnapshot.java') (Join-Path $source 'location\FiskentraLocationManager.java') (Join-Path $repo 'tests\TrackRecordingTest.java')
if($LASTEXITCODE -ne 0){throw 'TrackRecording compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json;$AndroidJar" com.fiskentra.app.data.TrackRecordingTest
if($LASTEXITCODE -ne 0){throw 'TrackRecording tests failed'}
