param([string]$Jdk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'build\backup-files-tests'
$json = Join-Path $repo 'build\test-deps\json-20240303.jar'
if (-not (Test-Path -LiteralPath $json)) { throw 'Run Test-RoutingParser.ps1 to prepare the shared JSON test dependency.' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $output `
    (Join-Path $repo 'app\src\main\java\com\fiskentra\app\backend\BackupFiles.java') `
    (Join-Path $repo 'tests\BackupFilesTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Backup file integrity tests compilation failed' }
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json" BackupFilesTest $output
if ($LASTEXITCODE -ne 0) { throw 'Backup file integrity tests failed' }
