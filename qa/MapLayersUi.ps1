param([Parameter(Mandatory=$true)][ValidateNotNullOrEmpty()][string]$Serial)

$ErrorActionPreference='Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'scripts\Android-Environment.ps1')
$script:LayerAdb=(Resolve-FiskentraAdb '')
$script:LayerSerial=$Serial
function Tap-LayerUi {
    param([string]$Text,[string]$Description)
    $remote='/sdcard/fiskentra-ui-'+[guid]::NewGuid().ToString('N')+'.xml'
    $dump=& $script:LayerAdb -s $script:LayerSerial shell uiautomator dump $remote
    if($LASTEXITCODE -ne 0 -or $dump -notmatch 'dumped to:'){throw 'No fresh UI snapshot'}
    $raw=& $script:LayerAdb -s $script:LayerSerial exec-out cat $remote
    [xml]$snapshot=$raw -join "`n"
    $nodes=@($snapshot.SelectNodes('//node') | Where-Object { ($Text -and $_.text -eq $Text) -or ($Description -and $_.'content-desc' -eq $Description) })
    if($nodes.Count -ne 1){throw ('Expected one UI target, found '+$nodes.Count+': '+$Text+$Description)}
    if($nodes[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'No UI bounds'}
    $x=[int](([int]$Matches[1]+[int]$Matches[3])/2)
    $y=[int](([int]$Matches[2]+[int]$Matches[4])/2)
    & $script:LayerAdb -s $script:LayerSerial shell input tap $x $y
    if($LASTEXITCODE -ne 0){throw 'Tap failed'}
    Write-Output ('Tapped: '+$Text+$Description)
}
