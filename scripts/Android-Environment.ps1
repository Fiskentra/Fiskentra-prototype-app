$ErrorActionPreference = 'Stop'

function Find-FiskentraToolsRoot {
    $directory = Get-Item -LiteralPath (Split-Path $PSScriptRoot -Parent)
    while ($directory) {
        $candidate = Join-Path $directory.FullName '.tools'
        if (Test-Path -LiteralPath $candidate -PathType Container) { return $candidate }
        $directory = $directory.Parent
    }
    return $null
}

function Resolve-FiskentraJdk([string]$Requested) {
    $candidates = @($Requested, $env:JAVA_HOME)
    $tools = Find-FiskentraToolsRoot
    if ($tools -and (Test-Path -LiteralPath (Join-Path $tools 'java'))) {
        $candidates += Get-ChildItem -LiteralPath (Join-Path $tools 'java') -Directory |
            Sort-Object Name -Descending | ForEach-Object FullName
    }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { $candidates += Split-Path (Split-Path $java.Source -Parent) -Parent }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'JDK not found. Set JAVA_HOME or pass -Jdk with an installed JDK directory.'
}

function Resolve-FiskentraSdk([string]$Requested) {
    $candidates = @($Requested, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $tools = Find-FiskentraToolsRoot
    if ($tools) { $candidates += Join-Path $tools 'android-sdk' }
    $properties = Join-Path (Split-Path $PSScriptRoot -Parent) 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $sdkLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) { $candidates += $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and ((Test-Path -LiteralPath (Join-Path $candidate 'platforms\android-37.0\android.jar')) -or
                (Test-Path -LiteralPath (Join-Path $candidate 'platforms\android-37\android.jar')))) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'Android SDK Platform 37 not found. Set ANDROID_HOME or pass -AndroidSdk with the SDK directory.'
}

function Resolve-FiskentraAndroidJar([string]$Requested) {
    if ($Requested) {
        if (-not (Test-Path -LiteralPath $Requested)) { throw 'The supplied AndroidJar does not exist.' }
        return (Resolve-Path -LiteralPath $Requested).Path
    }
    $sdk = Resolve-FiskentraSdk ''
    foreach ($platform in @('android-37.0', 'android-37')) {
        $jar = Join-Path $sdk ("platforms\$platform\android.jar")
        if (Test-Path -LiteralPath $jar) { return $jar }
    }
    throw 'Android SDK Platform 37 android.jar is missing.'
}

function Resolve-FiskentraAdb([string]$Requested) {
    $adb = $Requested
    if (-not $adb) { $adb = Join-Path (Resolve-FiskentraSdk '') 'platform-tools\adb.exe' }
    if (-not (Test-Path -LiteralPath $adb)) { throw 'adb not found. Install Android SDK platform-tools or pass its executable path.' }
    return (Resolve-Path -LiteralPath $adb).Path
}

function Initialize-FiskentraAndroidEnvironment([string]$Jdk, [string]$AndroidSdk) {
    $env:JAVA_HOME = Resolve-FiskentraJdk $Jdk
    $env:ANDROID_HOME = Resolve-FiskentraSdk $AndroidSdk
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + (Join-Path $env:ANDROID_HOME 'platform-tools') + ';' + $env:PATH
    $tools = Find-FiskentraToolsRoot
    if ($tools -and -not $env:ANDROID_USER_HOME) {
        $androidUser = Join-Path $tools 'android-user'
        if (Test-Path -LiteralPath $androidUser) { $env:ANDROID_USER_HOME = $androidUser }
    }
}
