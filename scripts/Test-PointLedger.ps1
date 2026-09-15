param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\point-ledger-tests'
$json=Join-Path $repo 'build\test-deps\json-20240303.jar'
if(-not (Test-Path -LiteralPath $json)){throw 'Run Test-RoutingParser.ps1 to prepare the JSON test dependency'}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source=Join-Path $repo 'app\src\main\java\com\fiskentra\app'
$files=@('data\PointLedger.java','model\SavedPoint.java','model\WeatherSnapshot.java','model\CatchDetails.java','model\CapturePolicy.java','model\LocationQualityPolicy.java','backend\SyncResponsePolicy.java') | ForEach-Object {Join-Path $source $_}
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $output $files (Join-Path $repo 'tests\PointLedgerTest.java')
if($LASTEXITCODE -ne 0){throw 'PointLedger compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json" PointLedgerTest
if($LASTEXITCODE -ne 0){throw 'PointLedger tests failed'}
