param(
    [string]$Jdk=$env:QUIET_JDK,
    [string]$BuildTools=$env:QUIET_BUILD_TOOLS,
    [string]$AndroidJar=$env:QUIET_ANDROID_JAR,
    [string]$FixtureSource=$env:QUIET_RECEIPT_FIXTURES
)
$ErrorActionPreference='Stop'
$taskRepo=Split-Path -Parent $PSScriptRoot
$taskWorkspace=Split-Path -Parent (Split-Path -Parent $taskRepo)
if(!$Jdk){$Jdk=(Get-ChildItem "$taskWorkspace\work\tools\jdk" -Directory | Select-Object -First 1).FullName}
if(!$BuildTools){$BuildTools="$taskWorkspace\work\tools\build-tools\android-11"}
if(!$AndroidJar){$AndroidJar="$taskWorkspace\work\tools\sdk-platform\android-12\android.jar"}
$mainClassesJar="$taskRepo\build\classes.jar"
$mainKeystore="$taskRepo\build\debug.keystore"
foreach($taskPath in @("$Jdk\bin\javac.exe","$Jdk\bin\jar.exe","$Jdk\bin\keytool.exe","$BuildTools\aapt.exe","$BuildTools\zipalign.exe","$BuildTools\lib\d8.jar","$BuildTools\lib\apksigner.jar",$AndroidJar,$mainClassesJar,$mainKeystore)){if(!(Test-Path -LiteralPath $taskPath)){throw "Missing test build dependency: $taskPath (run scripts/build.ps1 first)"}}

$taskOut="$taskRepo\build\tests"
if(!$FixtureSource){$FixtureSource="$taskRepo\samples\receipts"}
if(!(Test-Path -LiteralPath $FixtureSource)){throw "Missing receipt fixture source: $FixtureSource"}
$fixtureFiles=@(Get-ChildItem -LiteralPath $FixtureSource -Filter '*.png' -File | Sort-Object Name)
if($fixtureFiles.Count -ne 12){throw "Receipt fixture source must contain exactly 12 PNG files, found $($fixtureFiles.Count)"}
$assetRoot="$taskOut\assets"
$assetReceipts="$assetRoot\receipts"
New-Item -ItemType Directory -Force "$taskOut\classes","$taskOut\dex","$assetReceipts" | Out-Null
Get-ChildItem -LiteralPath $assetReceipts -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
foreach($fixture in $fixtureFiles){Copy-Item -LiteralPath $fixture.FullName -Destination (Join-Path $assetReceipts $fixture.Name) -Force}
$taskClasses=(Resolve-Path "$taskOut\classes").Path
if(!$taskClasses.StartsWith((Resolve-Path $taskOut).Path+[IO.Path]::DirectorySeparatorChar)){throw 'Invalid test compiler output path'}
Get-ChildItem -LiteralPath $taskClasses -Force | Remove-Item -Recurse -Force
$taskSources=Get-ChildItem "$taskRepo\tests\android\src" -Filter '*.java' -Recurse | ForEach-Object FullName
& "$Jdk\bin\javac.exe" --release 8 -encoding UTF-8 -classpath "$AndroidJar;$mainClassesJar" -d "$taskOut\classes" $taskSources
if($LASTEXITCODE){throw 'Instrumentation Java compilation failed'}
& "$Jdk\bin\jar.exe" cf "$taskOut\classes.jar" -C "$taskOut\classes" .
if($LASTEXITCODE){throw 'Instrumentation classes JAR failed'}
& "$Jdk\bin\java.exe" -cp "$BuildTools\lib\d8.jar" com.android.tools.r8.D8 --lib $AndroidJar --min-api 26 --output "$taskOut\dex" "$taskOut\classes.jar"
if($LASTEXITCODE){throw 'Instrumentation DEX compilation failed'}
& "$BuildTools\aapt.exe" package -f -M "$taskRepo\tests\android\AndroidManifest.xml" -I $AndroidJar -A $assetRoot -F "$taskOut\unsigned.apk"
if($LASTEXITCODE){throw 'Instrumentation APK packaging failed'}
& "$Jdk\bin\jar.exe" uf "$taskOut\unsigned.apk" -C "$taskOut\dex" classes.dex
if($LASTEXITCODE){throw 'Instrumentation DEX insertion failed'}
& "$BuildTools\zipalign.exe" -f 4 "$taskOut\unsigned.apk" "$taskOut\aligned.apk"
if($LASTEXITCODE){throw 'Instrumentation APK alignment failed'}
& "$Jdk\bin\java.exe" -jar "$BuildTools\lib\apksigner.jar" sign --ks $mainKeystore --ks-pass pass:android --key-pass pass:android --out "$taskOut\quiet-agent-tests.apk" "$taskOut\aligned.apk"
if($LASTEXITCODE){throw 'Instrumentation APK signing failed'}
& "$Jdk\bin\java.exe" -jar "$BuildTools\lib\apksigner.jar" verify "$taskOut\quiet-agent-tests.apk"
if($LASTEXITCODE){throw 'Instrumentation signature verification failed'}
Get-FileHash "$taskOut\quiet-agent-tests.apk" -Algorithm SHA256 | Format-List
