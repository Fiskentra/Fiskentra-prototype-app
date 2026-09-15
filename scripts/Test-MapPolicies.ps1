param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\map-policy-tests'
$json=Join-Path $repo 'build\test-deps\json-20240303.jar'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources=@('MapCameraPolicy','LocationQualityPolicy','MapFilterPolicy','TripEventPolicy','FishingDay','FieldNavigation','SavedPoint','WeatherSnapshot','CatchDetails') | ForEach-Object { Join-Path $repo ('app\src\main\java\com\fiskentra\app\model\'+$_+'.java') }
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $json -d $output $sources (Join-Path $repo 'tests\MapPoliciesTest.java')
if($LASTEXITCODE -ne 0){throw 'Map policy compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp ($output+';'+$json) MapPoliciesTest
if($LASTEXITCODE -ne 0){throw 'Map policy tests failed'}
