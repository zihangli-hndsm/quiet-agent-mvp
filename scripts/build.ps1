param([string]$Jdk=$env:QUIET_JDK,[string]$BuildTools=$env:QUIET_BUILD_TOOLS,[string]$AndroidJar=$env:QUIET_ANDROID_JAR)
$ErrorActionPreference='Stop'
$taskRepo=Split-Path -Parent $PSScriptRoot
$taskWorkspace=Split-Path -Parent (Split-Path -Parent $taskRepo)
if(!$Jdk){$Jdk=(Get-ChildItem "$taskWorkspace\work\tools\jdk" -Directory | Select-Object -First 1).FullName}
if(!$BuildTools){$BuildTools="$taskWorkspace\work\tools\build-tools\android-11"}
if(!$AndroidJar){$AndroidJar="$taskWorkspace\work\tools\sdk-platform\android-12\android.jar"}
foreach($taskPath in @("$Jdk\bin\javac.exe","$BuildTools\aapt.exe",$AndroidJar)){if(!(Test-Path -LiteralPath $taskPath)){throw "Missing build dependency: $taskPath"}}
$taskOut="$taskRepo\build"
New-Item -ItemType Directory -Force "$taskOut\classes","$taskOut\dex","$taskOut\core-tests" | Out-Null
# Empty only compiler output within this repository's build directory.
$taskClasses=(Resolve-Path "$taskOut\classes").Path
if(!$taskClasses.StartsWith((Resolve-Path $taskRepo).Path+[IO.Path]::DirectorySeparatorChar)){throw 'Invalid build output path'}
Get-ChildItem -LiteralPath $taskClasses -Force | Remove-Item -Recurse -Force
$taskSources=Get-ChildItem "$taskRepo\app\src" -Filter '*.java' -Recurse | ForEach-Object FullName
& "$Jdk\bin\javac.exe" '-J-Duser.language=en' --release 8 -encoding UTF-8 -classpath $AndroidJar -d "$taskOut\classes" @taskSources
if($LASTEXITCODE){throw 'Java compilation failed'}
& "$Jdk\bin\jar.exe" cf "$taskOut\classes.jar" -C "$taskOut\classes" .
& "$Jdk\bin\java.exe" -cp "$BuildTools\lib\d8.jar" com.android.tools.r8.D8 --lib $AndroidJar --min-api 26 --output "$taskOut\dex" "$taskOut\classes.jar"
if($LASTEXITCODE){throw 'DEX compilation failed'}
& "$BuildTools\aapt.exe" package -f -M "$taskRepo\app\AndroidManifest.xml" -I $AndroidJar -F "$taskOut\unsigned.apk"
if($LASTEXITCODE){throw 'APK packaging failed'}
& "$Jdk\bin\jar.exe" uf "$taskOut\unsigned.apk" -C "$taskOut\dex" classes.dex
& "$BuildTools\zipalign.exe" -f 4 "$taskOut\unsigned.apk" "$taskOut\aligned.apk"
if($LASTEXITCODE){throw 'ZIP alignment failed'}
if(!(Test-Path "$taskOut\debug.keystore")){
 & "$Jdk\bin\keytool.exe" -genkeypair -keystore "$taskOut\debug.keystore" -storepass android -keypass android -alias quiet -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Quiet Agent MVP'
 if($LASTEXITCODE){throw 'Test signing key generation failed'}
}
& "$Jdk\bin\java.exe" -jar "$BuildTools\lib\apksigner.jar" sign --ks "$taskOut\debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$taskOut\quiet-agent-mvp.apk" "$taskOut\aligned.apk"
if($LASTEXITCODE){throw 'APK signing failed'}
& "$Jdk\bin\java.exe" -jar "$BuildTools\lib\apksigner.jar" verify "$taskOut\quiet-agent-mvp.apk"
if($LASTEXITCODE){throw 'Signature verification failed'}
$taskPermissions=& "$BuildTools\aapt.exe" dump permissions "$taskOut\quiet-agent-mvp.apk"
if($taskPermissions -match 'android.permission.INTERNET'){throw 'Network permission must remain absent'}
$taskPermissions
Get-FileHash "$taskOut\quiet-agent-mvp.apk" -Algorithm SHA256 | Format-List
