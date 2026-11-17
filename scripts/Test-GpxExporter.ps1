param([string]$Jdk='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\gpx-exporter-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\export\GpxExporter.java') (Join-Path $repo 'tests\GpxExporterTest.java')
if($LASTEXITCODE -ne 0){throw 'GpxExporter compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp $output GpxExporterTest
if($LASTEXITCODE -ne 0){throw 'GpxExporter tests failed'}
