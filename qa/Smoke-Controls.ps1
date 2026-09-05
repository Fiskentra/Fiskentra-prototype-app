param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
function Read-Ui {
    $xmlText=(& $adb -s $Serial shell 'uiautomator dump /sdcard/fiskentra-smoke.xml >/dev/null && cat /sdcard/fiskentra-smoke.xml' | Out-String)
    if($LASTEXITCODE -ne 0 -or $xmlText -notmatch '<hierarchy'){throw 'Current UI unavailable'}
    [xml]$tree=$xmlText
    return $tree
}
function Tap-Node($tree,[string]$label) {
    $node=$tree.SelectNodes('//node') | Where-Object {$_.'content-desc' -eq $label} | Select-Object -First 1
    if(-not $node){$node=$tree.SelectNodes('//node') | Where-Object {$_.text -eq $label} | Select-Object -First 1}
    if(-not $node){throw "Control not visible: $label"}
    $bounds=[regex]::Matches($node.bounds,'\d+') | ForEach-Object {[int]$_.Value}
    & $adb -s $Serial shell input tap ([int](($bounds[0]+$bounds[2])/2)) ([int](($bounds[1]+$bounds[3])/2))
}
& $adb -s $Serial shell am start -n com.fiskentra.app/.MainActivity --ez qa_keep_awake true --es qa_route mapTools
$tree=Read-Ui
for($attempt=1;$attempt -le 2;$attempt++){
    Tap-Node $tree 'Offline map'
    $dialog=Read-Ui
    if(-not ($dialog.SelectNodes('//node') | Where-Object {$_.text -match 'Coming soon'})){throw 'Missing Coming soon explanation'}
    Tap-Node $dialog 'OK'
    $tree=Read-Ui
    $switch=$tree.SelectNodes('//node') | Where-Object {$_.'content-desc' -eq 'Offline map'} | Select-Object -First 1
    if($switch.checked -ne 'false'){throw 'Unavailable offline map incorrectly enabled'}
    "Offline toggle attempt ${attempt}: remains off"
}
Tap-Node $tree 'DONE'
$tree=Read-Ui
foreach($label in @('Saved','Journal','Devices','Forecast / Map')){
    Tap-Node $tree $label
    $tree=Read-Ui
    if(-not ($tree.SelectNodes('//node') | Where-Object {$_.package -eq 'com.fiskentra.app'})){throw "Navigation failed: $label"}
    $expected=switch($label){'Saved' {'Cloud synced|Local changes queued'} 'Journal' {'^Fishing journal$'} 'Devices' {'^Flic 2 button mapping$'} 'Forecast / Map' {'^Bite forecast$'}}
    if(-not ($tree.SelectNodes('//node') | Where-Object {$_.text -match $expected})){throw "Destination not confirmed: $label"}
    if($label -eq 'Saved'){$tree.SelectNodes('//node') | Where-Object {$_.text -match '^\d+ moments$'} | ForEach-Object {"Saved records visible: $($_.text)"}}
    "Navigation responsive: $label"
}
Tap-Node $tree 'Map'
$tree=Read-Ui
if(-not ($tree.SelectNodes('//node') | Where-Object {$_.'content-desc' -eq 'SAVE POINT'})){throw 'Map actions missing'}
'Map navigation responsive'
& $adb -s $Serial shell am start -n com.fiskentra.app/.MainActivity --es qa_route home --ez qa_keep_awake false
