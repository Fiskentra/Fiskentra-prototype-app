param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\road-route-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\FieldNavigation.java') (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\RoadRoute.java') (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\NavigationCue.java') (Join-Path $repo 'tests\RoadRouteTest.java')
if($LASTEXITCODE -ne 0){throw 'Road route compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp $output RoadRouteTest
if($LASTEXITCODE -ne 0){throw 'Road route tests failed'}
