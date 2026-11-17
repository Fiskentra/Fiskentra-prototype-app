param([string]$Jdk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'build\account-backup-ledger-tests'
$json = Join-Path $repo 'build\test-deps\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Run Test-RoutingParser.ps1 to prepare the shared JSON test dependency.' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source = Join-Path $repo 'app\src\main\java\com\fiskentra\app'
$files = @('data\PointLedger.java', 'model\SavedPoint.java', 'model\WeatherSnapshot.java',
    'model\CatchDetails.java', 'model\CapturePolicy.java', 'model\LocationQualityPolicy.java',
    'backend\SyncResponsePolicy.java', 'backend\AccountBackupLedger.java') |
    ForEach-Object { Join-Path $source $_ }
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $output $files (Join-Path $repo 'tests\AccountBackupLedgerTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Account backup ledger compilation failed' }
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json" AccountBackupLedgerTest
if ($LASTEXITCODE -ne 0) { throw 'Account backup ledger tests failed' }
