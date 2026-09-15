param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\offline-controller-tests'
$json=Join-Path $repo 'build\test-deps\json-20240303.jar'
if(-not (Test-Path -LiteralPath $json)){throw 'Run Test-RoutingParser.ps1 to prepare the shared JSON test dependency'}
New-Item -ItemType Directory -Force -Path $output | Out-Null
[xml]$resources=Get-Content -Raw (Join-Path $repo 'app\src\main\res\values\offline_area_strings.xml')
$fields=for($i=0;$i -lt $resources.resources.string.Count;$i++){ 'public static final int '+$resources.resources.string[$i].name+'='+($i+1)+';' }
$rSource=Join-Path $output 'R.java'
Set-Content -LiteralPath $rSource -Encoding utf8 -Value ('package com.fiskentra.app; public class R { public static class string {'+($fields -join '')+'} }')
$fakes=Get-ChildItem -LiteralPath (Join-Path $repo 'tests\offline') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
$source=Join-Path $repo 'app\src\main\java\com\fiskentra\app'
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $output $rSource $fakes (Join-Path $source 'model\OfflineAreaPolicy.java') (Join-Path $source 'offline\OfflineMapController.java') (Join-Path $repo 'tests\OfflineControllerTest.java')
if($LASTEXITCODE -ne 0){throw 'OfflineController compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json" OfflineControllerTest
if($LASTEXITCODE -ne 0){throw 'OfflineController checks failed'}
