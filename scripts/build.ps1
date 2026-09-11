param(
    [string]$Jdk = $env:QUIET_JDK,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$GradleHome = $env:QUIET_GRADLE
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$workspace = (Resolve-Path (Join-Path $repo '..\..')).Path
$tools = Join-Path $workspace 'work\tools'

if (-not $Jdk) { $Jdk = Join-Path $tools 'jdk\jdk-17.0.20.1+1' }
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $tools 'android-sdk' }
if (-not $GradleHome) { $GradleHome = Join-Path $tools 'gradle-8.9' }

$gradle = Join-Path $GradleHome 'bin\gradle.bat'
$aapt = Join-Path $AndroidSdk 'build-tools\35.0.0\aapt.exe'
foreach ($required in @($Jdk, $AndroidSdk, $gradle, $aapt)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build dependency: $required" }
}

$env:JAVA_HOME = (Resolve-Path $Jdk).Path
$env:ANDROID_HOME = (Resolve-Path $AndroidSdk).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

# Lint 31.7.3 is not present in the offline tool cache. The APK is still
# compiled, packaged, signed, and checked below; online CI can omit these -x flags.
$gradleArgs = @(
    ':app:clean', ':app:assembleDemo', '--offline', '--no-daemon', '--console=plain',
    '-x', 'lintVitalAnalyzeDemo', '-x', 'lintVitalReportDemo', '-x', 'lintVitalDemo'
)
& $gradle $gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle demo build failed with exit code $LASTEXITCODE" }

$apk = Join-Path $repo 'app\build\outputs\apk\demo\app-demo.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw "Demo APK was not produced: $apk" }

$permissions = (& $aapt dump permissions $apk | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect APK permissions' }
if ($permissions -match 'android\.permission\.INTERNET') {
    throw 'Demo APK must not request android.permission.INTERNET'
}

$manifest = (& $aapt dump xmltree $apk AndroidManifest.xml | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect merged AndroidManifest.xml' }
if ($manifest -match 'android:debuggable[^\r\n]*0x1') {
    throw 'Demo APK must be non-debuggable'
}
if ($manifest -notmatch 'android:allowBackup[^\r\n]*0x0') {
    throw 'Demo APK must set android:allowBackup=false'
}

$out = Join-Path $repo 'build\quiet-agent-demo.apk'
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
Copy-Item -LiteralPath $apk -Destination $out -Force

# Export the Gradle javac output for the dependency-free probe compiler. The
# test APK is signed with the same Gradle debug key used by the demo variant.
$classes = Join-Path $repo 'app\build\intermediates\javac\demo\compileDemoJavaWithJavac\classes'
if (-not (Test-Path -LiteralPath $classes)) { throw "Gradle classes were not produced: $classes" }
$classesJar = Join-Path $repo 'build\classes.jar'
& (Join-Path $env:JAVA_HOME 'bin\jar.exe') cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Unable to export Gradle classes.jar for probe build' }
$debugKeystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (Test-Path -LiteralPath $debugKeystore) {
    Copy-Item -LiteralPath $debugKeystore -Destination (Join-Path $repo 'build\debug.keystore') -Force
}

Write-Output "Demo APK: $out"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash)"
Write-Output 'Manifest checks: no INTERNET, non-debuggable, allowBackup=false'
