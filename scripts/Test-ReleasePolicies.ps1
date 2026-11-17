param([string]$Jdk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'build\release-policy-tests'
$json = Join-Path $repo 'build\test-deps\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Run Test-RoutingParser.ps1 to prepare the shared JSON test dependency.' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source = Join-Path $repo 'app\src\main\java\com\fiskentra\app'
$files = @('data\PointLedger.java', 'model\SavedPoint.java', 'model\WeatherSnapshot.java',
    'model\CatchDetails.java', 'model\CapturePolicy.java', 'model\LocationQualityPolicy.java',
    'model\BoatSpeedPolicy.java', 'model\BoatArrivalPolicy.java', 'model\WindLayerPolicy.java',
    'backend\SyncResponsePolicy.java', 'backend\BackendProjectSwitch.java') |
    ForEach-Object { Join-Path $source $_ }
$classes = @('BoatSpeedPolicyTest', 'BoatArrivalPolicyTest', 'WindLayerPolicyTest', 'BackendProjectSwitchTest')
$tests = $classes | ForEach-Object { Join-Path $repo ("tests\$_.java") }
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $output $files $tests
if ($LASTEXITCODE -ne 0) { throw 'Release policy compilation failed' }
foreach ($class in $classes) {
    & (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json" $class
    if ($LASTEXITCODE -ne 0) { throw "$class failed" }
}
