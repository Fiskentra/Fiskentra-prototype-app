param([string]$Jdk='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\catch-density-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\FieldNavigation.java') (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\CatchDensityGrid.java') (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\PastTripGeometry.java') (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\PastTripPalette.java') (Join-Path $repo 'tests\CatchDensityGridTest.java')
if($LASTEXITCODE -ne 0){throw 'Catch density compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp $output CatchDensityGridTest
if($LASTEXITCODE -ne 0){throw 'Catch density tests failed'}
