param([string]$Jdk=$env:QUIET_JDK)
$ErrorActionPreference='Stop'
$taskRepo=Split-Path -Parent $PSScriptRoot
$taskWorkspace=Split-Path -Parent (Split-Path -Parent $taskRepo)
if(!$Jdk){$Jdk=(Get-ChildItem "$taskWorkspace\work\tools\jdk" -Directory | Select-Object -First 1).FullName}
$taskOut="$taskRepo\build\core-tests"
New-Item -ItemType Directory -Force $taskOut | Out-Null
$taskCore=Get-ChildItem "$taskRepo\app\src\app\quietagent\core" -Filter '*.java' | ForEach-Object FullName
& "$Jdk\bin\javac.exe" '-J-Duser.language=en' --release 8 -encoding UTF-8 -d $taskOut @taskCore "$taskRepo\tests\CoreTests.java" "$taskRepo\tests\RunArchive.java"
if($LASTEXITCODE){throw 'Core test compilation failed'}
& "$Jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp $taskOut CoreTests
if($LASTEXITCODE){throw 'Core tests failed'}
