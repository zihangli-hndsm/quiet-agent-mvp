param([string]$Jdk=$env:QUIET_JDK,[string]$AndroidJar=$env:QUIET_ANDROID_JAR)
$ErrorActionPreference='Stop'
$taskRepo=Split-Path -Parent $PSScriptRoot
$taskWorkspace=Split-Path -Parent (Split-Path -Parent $taskRepo)
if(!$Jdk){$Jdk=(Get-ChildItem "$taskWorkspace\work\tools\jdk" -Directory | Select-Object -First 1).FullName}
if(!$AndroidJar){$AndroidJar="$taskWorkspace\work\tools\android-sdk\platforms\android-35\android.jar"}
$taskOut="$taskRepo\build\core-tests"
New-Item -ItemType Directory -Force $taskOut | Out-Null
$taskCore=Get-ChildItem "$taskRepo\app\src\app\quietagent\core" -Filter '*.java' | ForEach-Object FullName
$taskSecurity=Get-ChildItem "$taskRepo\app\src\app\quietagent\security" -Filter '*.java' | ForEach-Object FullName
$taskReceipt=Get-ChildItem "$taskRepo\app\src\app\quietagent\receipt" -Filter '*.java' | ForEach-Object FullName
& "$Jdk\bin\javac.exe" '-J-Duser.language=en' --release 8 -encoding UTF-8 -classpath $AndroidJar -d $taskOut @taskCore @taskSecurity @taskReceipt "$taskRepo\tests\CoreTests.java" "$taskRepo\tests\RunArchive.java" "$taskRepo\tests\SecurityTests.java" "$taskRepo\tests\ReceiptTests.java"
if($LASTEXITCODE){throw 'Core test compilation failed'}
& "$Jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp $taskOut CoreTests
if($LASTEXITCODE){throw 'Core tests failed'}
& "$Jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$taskOut;$AndroidJar" SecurityTests
if($LASTEXITCODE){throw 'Security tests failed'}
& "$Jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$taskOut;$AndroidJar" ReceiptTests
if($LASTEXITCODE){throw 'Receipt tests failed'}
