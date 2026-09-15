param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\map-debug-tools-tests'
$json=Join-Path $repo 'build\test-deps\json-20240303.jar'
if(-not (Test-Path -LiteralPath $json)){throw 'Run Test-RoutingParser.ps1 to prepare the shared JSON test dependency'}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source=Join-Path $repo 'app\src\main\java\com\fiskentra\app'
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $output (Join-Path $repo 'tests\offline\com\fiskentra\app\BuildConfig.java') (Join-Path $source 'model\SavedPoint.java') (Join-Path $source 'model\WeatherSnapshot.java') (Join-Path $source 'model\CatchDetails.java') (Join-Path $source 'model\FieldNavigation.java') (Join-Path $source 'debug\MapDebugFixture.java') (Join-Path $source 'debug\FrameTimingStats.java') (Join-Path $repo 'tests\MapDebugToolsTest.java')
if($LASTEXITCODE -ne 0){throw 'MapDebugTools compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json" MapDebugToolsTest
if($LASTEXITCODE -ne 0){throw 'MapDebugTools checks failed'}
