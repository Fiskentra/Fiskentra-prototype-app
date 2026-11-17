param([string]$Jdk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'build\field-access-policy-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d $output `
    (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\FieldAccessPolicy.java') `
    (Join-Path $repo 'tests\FieldAccessPolicyTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Field access policy compilation failed' }
& (Join-Path $Jdk 'bin\java.exe') -cp $output FieldAccessPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Field access policy tests failed' }
