param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference='Stop'
$adb='C:\Users\Gebruiker\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$peak=(& $adb -s $Serial shell settings get system peak_refresh_rate | Out-String).Trim()
$minimum=(& $adb -s $Serial shell settings get system min_refresh_rate | Out-String).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read original refresh settings'}
$output=Join-Path $PSScriptRoot 'map-beta/performance'
New-Item -ItemType Directory -Force -Path $output | Out-Null
@{peak=$peak;minimum=$minimum} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $output 'original-refresh.json')
try {
    & $adb -s $Serial shell settings put system peak_refresh_rate 60
    & $adb -s $Serial shell settings put system min_refresh_rate 60
    & (Join-Path $PSScriptRoot 'Measure-MapPerformance.ps1') -Serial $Serial -OutputDirectory $output
} finally {
    if($peak -eq 'null'){& $adb -s $Serial shell settings delete system peak_refresh_rate}else{& $adb -s $Serial shell settings put system peak_refresh_rate $peak}
    if($minimum -eq 'null'){& $adb -s $Serial shell settings delete system min_refresh_rate}else{& $adb -s $Serial shell settings put system min_refresh_rate $minimum}
}
