param([switch]$Offline, [string]$Jdk = '', [string]$AndroidSdk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
Initialize-FiskentraAndroidEnvironment $Jdk $AndroidSdk
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = Join-Path (Split-Path $projectRoot -Parent) '.jtmp'
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$env:TEMP = $tempRoot
$env:TMP = $tempRoot
$tempOption = '-Djava.io.tmpdir="' + $tempRoot + '"'
$env:JAVA_TOOL_OPTIONS = (@($env:JAVA_TOOL_OPTIONS, $tempOption) | Where-Object { $_ }) -join ' '
$env:GRADLE_USER_HOME = Join-Path (Split-Path $projectRoot -Parent) '.gradle-codex'
Push-Location $projectRoot
try {
    $buildArgs = @('assembleDebug', 'lintDebug', 'lintRelease', '--no-daemon')
    if ($Offline) { $buildArgs += '--offline' }
    & .\gradlew.bat @buildArgs
    exit $LASTEXITCODE
} finally { Pop-Location }
