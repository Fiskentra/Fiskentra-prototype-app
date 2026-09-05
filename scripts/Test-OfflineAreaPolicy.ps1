param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\offline-area-policy-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\OfflineAreaPolicy.java') (Join-Path $repo 'tests\OfflineAreaPolicyTest.java')
if($LASTEXITCODE -ne 0){throw 'OfflineAreaPolicy compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp $output OfflineAreaPolicyTest
if($LASTEXITCODE -ne 0){throw 'OfflineAreaPolicy tests failed'}
