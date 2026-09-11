param([string]$Adb=$env:QUIET_ADB,[switch]$SkipBuild,[switch]$KeepFixtures)
$ErrorActionPreference='Stop'
$taskRepo=Split-Path -Parent $PSScriptRoot
$taskWorkspace=Split-Path -Parent (Split-Path -Parent $taskRepo)
if(!$Adb){$Adb="$taskWorkspace\work\tools\platform-tools\adb.exe"}
if(!$SkipBuild){& "$PSScriptRoot\build.ps1"; & "$PSScriptRoot\build-tests.ps1"}
foreach($apk in @("$taskRepo\build\quiet-agent-mvp.apk","$taskRepo\build\tests\quiet-agent-tests.apk")){
 & $Adb install -r -t $apk
 if($LASTEXITCODE){throw 'APK installation failed'}
}
$taskResults="$taskRepo\build\device-tests-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff'))"
New-Item -ItemType Directory $taskResults | Out-Null
foreach($scenario in @('smoke','cancel','external','export')){
 $extra=@();if($KeepFixtures -and $scenario -eq 'external'){$extra=@('-e','keepFixtures','true')}
 $result=& $Adb shell am instrument -w -e scenario $scenario @extra app.quietagent.test/app.quietagent.test.QuietInstrumentation
 $result | Set-Content -Encoding utf8 "$taskResults\$scenario.txt"
 $jsonLine=$result | Where-Object {$_ -like 'INSTRUMENTATION_RESULT: result=*'} | Select-Object -Last 1
 if(!$jsonLine){throw "No structured result for $scenario; see $taskResults"}
 $parsed=$jsonLine.Substring('INSTRUMENTATION_RESULT: result='.Length) | ConvertFrom-Json
 $parsed | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 "$taskResults\$scenario.json"
 if(!$parsed.ok){throw "$scenario failed: $($parsed.error); see $taskResults"}
 Write-Host "PASS $scenario"
}
Write-Host "Evidence: $taskResults"
