param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\trip-statistics-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $Jdk 'bin\javac.exe') -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\TripStatistics.java') (Join-Path $repo 'tests\TripStatisticsTest.java')
if($LASTEXITCODE -ne 0){throw 'TripStatistics compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp $output TripStatisticsTest
if($LASTEXITCODE -ne 0){throw 'TripStatistics tests failed'}
