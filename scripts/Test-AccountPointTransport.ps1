param([string]$Jdk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'build\account-point-transport-tests'
$json = Join-Path $repo 'build\test-deps\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Run Test-RoutingParser.ps1 to prepare the shared JSON test dependency.' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source = Join-Path $repo 'app\src\main\java\com\fiskentra\app'
$files = @('data\PointLedger.java', 'model\SavedPoint.java', 'model\WeatherSnapshot.java',
    'model\CatchDetails.java', 'model\CapturePolicy.java', 'model\LocationQualityPolicy.java',
    'backend\CloudDataPolicy.java', 'backend\CloudPointCodec.java', 'backend\CloudSyncLedger.java',
    'backend\AccountPointTransport.java') | ForEach-Object { Join-Path $source $_ }
$stubs = Get-ChildItem -LiteralPath (Join-Path $repo 'tests\accounttransport') -Filter '*.java' -Recurse -File |
    Select-Object -ExpandProperty FullName
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $output $files $stubs (Join-Path $repo 'tests\AccountPointTransportTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Account point transport integration compilation failed' }
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json" com.fiskentra.app.backend.AccountPointTransportTest
if ($LASTEXITCODE -ne 0) { throw 'Account point transport integration tests failed' }
