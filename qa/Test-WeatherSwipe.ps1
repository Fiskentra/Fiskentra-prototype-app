param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference='Stop'
$driver=Join-Path $PSScriptRoot 'Invoke-FiskentraDevice.ps1'
function Assert-Screen([bool]$Map,[string]$Description) {
    $snapshot=(& $driver -Serial $Serial -Action Snapshot | ConvertFrom-Json)
    $isMap=@($snapshot | Where-Object { $_.description -eq 'Map mode and tools' }).Count -eq 1
    $isForecast=@($snapshot | Where-Object { $_.text -eq 'Forecast' }).Count -gt 0
    if(($Map -and -not $isMap) -or (-not $Map -and -not $isForecast)){throw "FAIL: $Description"}
    Write-Output "PASS: $Description"
}
Assert-Screen $true 'Map is open'
& $driver -Serial $Serial -Action Swipe -X 980 -Y 900 -EndX 200 -EndY 900
Assert-Screen $false 'Map left edge swipe opens forecast'
& $driver -Serial $Serial -Action Swipe -X 200 -Y 900 -EndX 850 -EndY 900
Assert-Screen $true 'Forecast right swipe opens map'
& $driver -Serial $Serial -Action Swipe -X 100 -Y 900 -EndX 850 -EndY 900
Assert-Screen $false 'Map right edge swipe opens forecast'
& $driver -Serial $Serial -Action Swipe -X 850 -Y 900 -EndX 200 -EndY 900
Assert-Screen $true 'Forecast left swipe opens map'
& $driver -Serial $Serial -Action Swipe -X 540 -Y 900 -EndX 850 -EndY 900
Assert-Screen $true 'Dragging the map body keeps the map open'
& $driver -Serial $Serial -Action Tap -Label 'Weather forecast'
Assert-Screen $false 'Header weather button opens forecast'
