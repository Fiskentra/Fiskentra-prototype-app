param([string]$Jdk='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\marine-tile-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\MarineTilePolicy.java') (Join-Path $repo 'tests\MarineTilePolicyTest.java')
if($LASTEXITCODE -ne 0){throw 'Marine tile policy compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp $output MarineTilePolicyTest
if($LASTEXITCODE -ne 0){throw 'Marine tile policy tests failed'}
