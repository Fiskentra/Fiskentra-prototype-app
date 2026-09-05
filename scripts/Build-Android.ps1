param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$tempRoot = Join-Path (Split-Path $projectRoot -Parent) '.jtmp'
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$env:TEMP = $tempRoot
$env:TMP = $tempRoot
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$tempRoot"
if (-not $env:JAVA_HOME) {
    $jdk = Join-Path $env:USERPROFILE '.jdks\jbr-21.0.11'
    if (Test-Path -LiteralPath (Join-Path $jdk 'bin\java.exe')) { $env:JAVA_HOME = $jdk }
}
$env:GRADLE_USER_HOME = Join-Path (Split-Path $projectRoot -Parent) '.gradle-codex'
Push-Location $projectRoot
try {
    $buildArgs = @('assembleDebug', 'lintDebug', '--no-daemon')
    if ($Offline) { $buildArgs += '--offline' }
    & .\gradlew.bat @buildArgs
    exit $LASTEXITCODE
} finally { Pop-Location }
