param([string]$Jdk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'build\recovery-checks'
New-Item -ItemType Directory -Force -Path $output | Out-Null
# Prepare the existing shared test dependency before the suites that use it.
$scripts = @((Join-Path $PSScriptRoot 'Test-RoutingParser.ps1'))
$scripts += Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'Test-*.ps1' -File |
    Where-Object { $_.Name -notin @('Test-AndroidCore.ps1', 'Test-RoutingParser.ps1') } |
    Sort-Object Name | ForEach-Object FullName
$results = foreach ($script in $scripts) {
    $name = [IO.Path]::GetFileNameWithoutExtension($script)
    try {
        & $script -Jdk $Jdk *> (Join-Path $output "$name.log")
        [pscustomobject]@{ Test = $name; Result = 'PASS'; Error = $null }
    } catch {
        [pscustomobject]@{ Test = $name; Result = 'FAIL'; Error = $_.Exception.Message }
    }
}
$results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'results.json') -Encoding utf8
$results | Format-Table -AutoSize
if (@($results | Where-Object Result -eq 'FAIL').Count -gt 0) { throw 'Android core checks failed. See build/recovery-checks/results.json and per-suite logs.' }
