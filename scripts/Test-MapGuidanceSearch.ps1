param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\map-guidance-search-tests'
$jar=Join-Path $repo 'build\test-deps\json-20240303.jar'
New-Item -ItemType Directory -Force -Path $output | Out-Null
if(-not (Test-Path -LiteralPath $jar)){throw 'Run Test-RoutingParser.ps1 once to obtain the existing test JSON dependency.'}
$source=Join-Path $repo 'app\src\main\java\com\fiskentra\app'
$files=@("$source\model\FieldNavigation.java","$source\model\BacktrackRoute.java","$source\model\BacktrackProgress.java","$source\navigation\BacktrackCodec.java")
$files+=@(Get-ChildItem -LiteralPath "$source\search" -Filter '*.java' | ForEach-Object FullName)
$files+=@("$repo\tests\BacktrackTest.java","$repo\tests\PlaceSearchTest.java")
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $jar -d $output $files
if($LASTEXITCODE -ne 0){throw 'Map guidance/search compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$jar" BacktrackTest
if($LASTEXITCODE -ne 0){throw 'Backtrack tests failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$jar" PlaceSearchTest
if($LASTEXITCODE -ne 0){throw 'Place search tests failed'}
