param([string]$Jdk='C:\Users\Gebruiker\.jdks\jbr-21.0.11')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo 'build\routing-parser-tests'
$jar=Join-Path $repo 'build\test-deps\json-20240303.jar'
New-Item -ItemType Directory -Force -Path $output,(Split-Path $jar) | Out-Null
if(-not (Test-Path $jar)) { Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $jar }
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -cp $jar -d $output (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\FieldNavigation.java') (Join-Path $repo 'app\src\main\java\com\fiskentra\app\model\RoadRoute.java') (Join-Path $repo 'app\src\main\java\com\fiskentra\app\navigation\ValhallaParser.java') (Join-Path $repo 'tests\ValhallaParserTest.java')
if($LASTEXITCODE -ne 0){throw 'Routing parser compilation failed'}
& (Join-Path $Jdk 'bin\java.exe') -cp "$output;$jar" ValhallaParserTest (Join-Path $repo 'tests\fixtures')
if($LASTEXITCODE -ne 0){throw 'Routing parser tests failed'}
