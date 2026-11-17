param(
    [string]$Jdk='',
    [string]$AndroidJar=''
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Android-Environment.ps1')
$Jdk = Resolve-FiskentraJdk $Jdk
$AndroidJar = Resolve-FiskentraAndroidJar $AndroidJar
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\weather-wind-cache-tests'
$json=Join-Path $repo 'build\test-deps\json-20240303.jar'
if(-not (Test-Path -LiteralPath $json)){throw 'Run Test-RoutingParser.ps1 to prepare the shared JSON test dependency'}
if(-not (Test-Path -LiteralPath $AndroidJar)){throw 'Pass -AndroidJar with the installed Android SDK android.jar path'}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source=Join-Path $repo 'app\src\main\java\com\fiskentra\app'
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp "$json;$AndroidJar" -d $output (Join-Path $repo 'tests\weather\android\content\Context.java') (Join-Path $repo 'tests\weather\android\location\Location.java') (Join-Path $source 'model\FieldNavigation.java') (Join-Path $source 'model\WeatherSnapshot.java') (Join-Path $source 'model\ForecastDay.java') (Join-Path $source 'model\WeatherForecast.java') (Join-Path $source 'model\WindLayerPolicy.java') (Join-Path $source 'weather\WeatherClient.java') (Join-Path $repo 'tests\WeatherWindCacheTest.java')
if($LASTEXITCODE -ne 0){throw 'Weather wind cache compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$json;$AndroidJar" WeatherWindCacheTest
if($LASTEXITCODE -ne 0){throw 'Weather wind cache tests failed'}
