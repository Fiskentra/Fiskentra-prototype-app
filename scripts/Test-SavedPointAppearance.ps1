param([string]$Jdk='', [string]$AndroidJar='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$AndroidJar = Resolve-FiskentraAndroidJar $AndroidJar
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\point-appearance-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources=@('SavedPoint','WeatherSnapshot','CatchDetails') | ForEach-Object { Join-Path $repo ('app\src\main\java\com\fiskentra\app\model\'+$_+'.java') }
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $AndroidJar -d $output $sources (Join-Path $repo 'tests\SavedPointAppearanceTest.java')
if($LASTEXITCODE -ne 0){throw 'Point appearance compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp ($output+';'+$AndroidJar) SavedPointAppearanceTest
if($LASTEXITCODE -ne 0){throw 'Point appearance tests failed'}
