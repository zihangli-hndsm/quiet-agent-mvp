param(
    [string]$Jdk = $env:QUIET_JDK,
    [string]$Python = $env:QUIET_PYTHON
)

$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskWorkspace = Split-Path -Parent (Split-Path -Parent $taskRepo)
if (!$Jdk) {
    $Jdk = (Get-ChildItem "$taskWorkspace\work\tools\jdk" -Directory | Select-Object -First 1).FullName
}
if (!$Jdk -or !(Test-Path (Join-Path $Jdk 'bin\java.exe'))) {
    throw "JDK not found; pass -Jdk or set QUIET_JDK"
}
if (!$Python) { $Python = 'python' }

$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff')
$integrationRoot = Join-Path $taskRepo "build\integration-$stamp"
New-Item -ItemType Directory -Path $integrationRoot -ErrorAction Stop | Out-Null
$transcript = Join-Path $integrationRoot 'integration.log'
Start-Transcript -Path $transcript -Force | Out-Null

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $false)][string[]]$Arguments = @()
    )
    & $File @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "command failed with exit code $LASTEXITCODE`: $File $($Arguments -join ' ')"
    }
}

try {
    Write-Host "integration output: $integrationRoot"
    & (Join-Path $PSScriptRoot 'test-core.ps1') -Jdk $Jdk
    if ($LASTEXITCODE -ne 0) { throw "test-core.ps1 failed with exit code $LASTEXITCODE" }

    $fixtureScript = Join-Path $taskRepo 'tests\make_fixtures.py'
    $verifierScript = Join-Path $taskRepo 'tests\verify_archive.py'
    $classes = Join-Path $taskRepo 'build\core-tests'
    foreach ($mode in @('small', 'stress')) {
        $fixtureRoot = Join-Path $integrationRoot $mode
        $destination = Join-Path $fixtureRoot 'destination'
        Invoke-Checked $Python @($fixtureScript, '--mode', $mode, '--output-dir', $fixtureRoot)
        New-Item -ItemType Directory -Path $destination -ErrorAction Stop | Out-Null
        Invoke-Checked (Join-Path $Jdk 'bin\java.exe') @('-Dfile.encoding=UTF-8', '-cp', $classes, 'RunArchive', (Join-Path $fixtureRoot 'source'), $destination)
        Invoke-Checked $Python @($verifierScript,
            '--source-dir', (Join-Path $fixtureRoot 'source'),
            '--archive', (Join-Path $destination 'archive.zip'),
            '--manifest', (Join-Path $destination 'manifest.json'),
            '--expected', (Join-Path $fixtureRoot 'expected.json'))
        Write-Host "verified $mode fixture at $fixtureRoot"
    }
    Write-Host "PASS: integration artifacts retained at $integrationRoot"
}
finally {
    Stop-Transcript | Out-Null
}
