$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$workspace=(Resolve-Path (Join-Path $repo '..\..')).Path
$tools=Join-Path $workspace 'work\tools'
$jdk=$env:QUIET_JDK
if(!$jdk){$jdk=(Get-ChildItem (Join-Path $tools 'jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName)}
if(!$jdk -and $env:JAVA_HOME){$jdk=$env:JAVA_HOME}
if(!$jdk){throw 'Missing bundled JDK; set QUIET_JDK'}
$android=(Get-ChildItem (Join-Path $tools 'sdk-platform') -Filter android.jar -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName)
if(!$android){throw 'Missing android.jar'}
$json=(Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\caches') -Filter 'json-*.jar' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName)
if(!$json){$deps=Join-Path $repo 'build\test-deps';New-Item -ItemType Directory -Force $deps|Out-Null;$json=Join-Path $deps 'json-20240303.jar';if(!(Test-Path $json)){Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $json}}
$out=Join-Path $repo 'build\host-storage'; New-Item -ItemType Directory -Force $out | Out-Null
$src=@((Join-Path $repo 'app\src\app\quietagent\workspace\WorkspaceFileAccess.java'),(Join-Path $repo 'app\src\app\quietagent\workspace\WorkspaceStore.java'),(Join-Path $repo 'app\src\app\quietagent\workspace\ToolExecutor.java'),(Join-Path $repo 'app\src\app\quietagent\workspace\ToolCallEnvelope.java'),(Join-Path $repo 'app\src\app\quietagent\workspace\CsvTableParser.java'),(Join-Path $repo 'tests\WorkspaceStoreHostTests.java'),(Join-Path $repo 'tests\ToolExecutorHostTests.java'),(Join-Path $repo 'tests\ToolCallEnvelopeTests.java'),(Join-Path $repo 'tests\CsvTableParserTests.java'))
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -cp "$json;$android;$(Join-Path $repo 'build\classes.jar')" -d $out $src
if($LASTEXITCODE){throw 'Host compilation failed'}
& (Join-Path $jdk 'bin\java.exe') -cp "$json;$android;$out;$(Join-Path $repo 'build\classes.jar')" WorkspaceStoreHostTests
if($LASTEXITCODE){throw 'Host tests failed'}
& (Join-Path $jdk 'bin\java.exe') -cp "$json;$android;$out;$(Join-Path $repo 'build\classes.jar')" ToolExecutorHostTests
if($LASTEXITCODE){throw 'Tool host tests failed'}
& (Join-Path $jdk 'bin\java.exe') -cp "$json;$android;$out;$(Join-Path $repo 'build\classes.jar')" app.quietagent.workspace.ToolCallEnvelopeTests
if($LASTEXITCODE){throw 'Tool call envelope tests failed'}
& (Join-Path $jdk 'bin\java.exe') -cp "$json;$android;$out;$(Join-Path $repo 'build\classes.jar')" app.quietagent.workspace.CsvTableParserTests
if($LASTEXITCODE){throw 'CSV table parser tests failed'}
