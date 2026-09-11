param(
    [string]$Python = $env:QUIET_PYTHON
)

$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
if (!$Python) { $Python = 'python' }
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff')
$output = Join-Path $taskRepo "build\receipt-demo-$stamp"
New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
$log = "$output.log"
Start-Transcript -Path $log -Force | Out-Null

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments = @())
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "command failed with exit code $LASTEXITCODE`: $File $($Arguments -join ' ')" }
}

try {
    $generator = Join-Path $taskRepo 'tests\receipt_fixtures\generate_receipts.py'
    $verifier = Join-Path $taskRepo 'tests\verify_receipt_package.py'
    $expected = Join-Path $taskRepo 'tests\receipt_fixtures\expected.json'
    Invoke-Checked $Python @($generator, '--output-dir', $output)
    Invoke-Checked $Python @($verifier,
        '--package', (Join-Path $output 'receipt-package.zip'),
        '--source-dir', (Join-Path $output 'source'),
        '--expected', $expected)
    Write-Host "PASS: receipt demo retained at $output"
}
finally {
    Stop-Transcript | Out-Null
}
