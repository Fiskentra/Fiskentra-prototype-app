param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\field-navigation-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\FieldNavigation.java') (Join-Path $repo 'tests\FieldNavigationTest.java')
if($LASTEXITCODE -ne 0){throw 'FieldNavigation compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp $output FieldNavigationTest
if($LASTEXITCODE -ne 0){throw 'FieldNavigation tests failed'}
