param(
    [ValidateSet('Snapshot','Tap','MapTap','Swipe','Screenshot')][string]$Action='Snapshot',
    [string]$Label,
    [string]$OutputPath,
    [int]$X, [int]$Y, [int]$EndX, [int]$EndY,
    [Parameter(Mandatory=$true)][string]$Serial
)
$ErrorActionPreference='Stop'
$adb='C:\Users\Gebruiker\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dumpResult=(& $adb -s $Serial shell uiautomator dump /sdcard/fiskentra-qa.xml 2>&1 | Out-String)
if($LASTEXITCODE -ne 0 -or $dumpResult -notmatch 'dumped to:' -or $dumpResult -match 'ERROR:') { throw 'Cannot inspect fresh device UI; refusing stale snapshot' }
[xml]$ui=(& $adb -s $Serial shell cat /sdcard/fiskentra-qa.xml)
$nodes=@($ui.SelectNodes('//node'))
if(-not ($nodes | Where-Object { $_.package -eq 'com.fiskentra.app' })) { throw 'Fiskentra is not foreground; refusing UI interaction' }
if($Action -eq 'Snapshot') {
    $nodes | Where-Object { $_.package -eq 'com.fiskentra.app' -and ($_.text -or $_.'content-desc') } | ForEach-Object { [pscustomobject]@{text=$_.text;description=$_.'content-desc';bounds=$_.bounds;enabled=$_.enabled} } | ConvertTo-Json -Compress
} elseif($Action -eq 'Tap') {
    $matches=@($nodes | Where-Object { $_.package -eq 'com.fiskentra.app' -and ($_.text -ceq $Label -or $_.'content-desc' -ceq $Label) -and $_.enabled -eq 'true' })
    if($matches.Count -ne 1){throw "Expected one enabled target '$Label', found $($matches.Count)"}
    $bounds=[regex]::Matches($matches[0].bounds,'\d+') | ForEach-Object { [int]$_.Value }
    & $adb -s $Serial shell input tap ([int](($bounds[0]+$bounds[2])/2)) ([int](($bounds[1]+$bounds[3])/2))
} elseif($Action -eq 'Swipe') {
    & $adb -s $Serial shell input swipe $X $Y $EndX $EndY 400
} elseif($Action -eq 'MapTap') {
    $map=@($nodes | Where-Object { $_.package -eq 'com.fiskentra.app' -and $_.'content-desc' -like '*MapLibre*' })
    if($map.Count -ne 1){throw 'Map surface is not visible'}
    $bounds=[regex]::Matches($map[0].bounds,'\d+') | ForEach-Object { [int]$_.Value }
    if($X -le $bounds[0] -or $X -ge $bounds[2] -or $Y -le $bounds[1] -or $Y -ge $bounds[3]){throw 'Tap outside map'}
    & $adb -s $Serial shell input tap $X $Y
} else {
    if(-not $OutputPath){throw 'OutputPath required'}
    & $adb -s $Serial shell screencap -p /sdcard/fiskentra-qa.png
    & $adb -s $Serial pull /sdcard/fiskentra-qa.png $OutputPath
}
if($LASTEXITCODE -ne 0){throw 'Device command failed'}
