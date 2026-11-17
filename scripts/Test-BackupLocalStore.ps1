param([string]$Jdk = '', [string]$AndroidJar = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$AndroidJar = Resolve-FiskentraAndroidJar $AndroidJar
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'build\backup-local-tests'
$json = Join-Path $repo 'build\test-deps\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Run Test-RoutingParser.ps1 to prepare the JSON test dependency.' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source = Join-Path $repo 'app\src\main\java\com\fiskentra\app'
$files = @('data\PointLedger.java','data\FishingDayStore.java','data\TrackStore.java','model\SavedPoint.java',
    'model\CatchDetails.java','model\WeatherSnapshot.java','model\FishingDay.java','model\TrackPointPolicy.java',
    'model\CapturePolicy.java','model\LocationQualityPolicy.java','location\FiskentraLocationManager.java',
    'backend\CloudPointCodec.java','backend\AccountBackupLedger.java','backend\BackupCodec.java','backend\BackupLocalStore.java') |
    ForEach-Object { Join-Path $source $_ }
$stubs = Get-ChildItem -LiteralPath (Join-Path $repo 'tests\backuplocal') -Recurse -Filter '*.java' | ForEach-Object FullName
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp "$json;$AndroidJar" -d $output $files $stubs (Join-Path $repo 'tests\BackupLocalStoreTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Backup local-store compilation failed' }
Push-Location $repo
try { & (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json;$AndroidJar" com.fiskentra.app.data.BackupLocalStoreTest }
finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Backup local-store tests failed' }
