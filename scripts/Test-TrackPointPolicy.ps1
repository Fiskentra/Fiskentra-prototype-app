param([string]$Jdk='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\track-point-policy-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\TrackPointPolicy.java') (Join-Path $repo 'tests\TrackPointPolicyTest.java')
if($LASTEXITCODE -ne 0){throw 'TrackPointPolicy compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp $output TrackPointPolicyTest
if($LASTEXITCODE -ne 0){throw 'TrackPointPolicy tests failed'}
