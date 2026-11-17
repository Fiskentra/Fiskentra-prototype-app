param([string]$Jdk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$androidJar = Resolve-FiskentraAndroidJar ''
$repo = Split-Path $PSScriptRoot -Parent
$source = Join-Path $repo 'app\src\main\java\com\fiskentra\app'
$output = Join-Path $repo 'build\hazard-proximity-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @(
    (Join-Path $source 'model\FieldNavigation.java')
    (Join-Path $source 'model\LocationQualityPolicy.java')
    (Join-Path $source 'model\PointMetadata.java')
    (Join-Path $source 'model\MapHazard.java')
    (Join-Path $source 'model\HazardPolygonGeometry.java')
    (Join-Path $source 'location\HazardProximityMonitor.java')
    (Join-Path $repo 'tests\HazardProximityTest.java')
)
& (Join-Path $Jdk 'bin\javac.exe') -cp $androidJar -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Hazard proximity compilation failed' }
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$androidJar" HazardProximityTest
if ($LASTEXITCODE -ne 0) { throw 'Hazard proximity checks failed' }
