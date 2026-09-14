param([string]$Jdk = $env:QUIET_JDK)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$workspace = (Resolve-Path (Join-Path $repo '..\..')).Path
if (-not $Jdk) { $Jdk = Join-Path $workspace 'work\tools\jdk\jdk-17.0.20.1+1' }
$javac = Join-Path $Jdk 'bin\javac.exe'; $java = Join-Path $Jdk 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) { throw "Missing JDK: $Jdk" }
$out = Join-Path $repo '.tmp-workspace-test-run'
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
  & $javac -encoding UTF-8 -d $out (Join-Path $repo 'app\src\app\quietagent\workspace\ControlledWorkspace.java') (Join-Path $repo 'tests\WorkspaceTests.java')
  if ($LASTEXITCODE -ne 0) { throw 'Workspace javac failed' }
  & $java -cp $out WorkspaceTests
  if ($LASTEXITCODE -ne 0) { throw 'Workspace tests failed' }
} finally { if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force } }
