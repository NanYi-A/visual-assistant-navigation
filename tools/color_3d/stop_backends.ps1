$ErrorActionPreference = 'Stop'
$taskOutput = Join-Path $PSScriptRoot 'output'
$taskPidFile = Join-Path $taskOutput 'watchdog.pid'
$taskStatusFile = Join-Path $taskOutput 'watchdog.json'

# A stale PID may have been reused, so verify the command line first.
if (Test-Path -LiteralPath $taskPidFile) {
    $taskWatchdogPid = [int](Get-Content -LiteralPath $taskPidFile -Raw)
    $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$taskWatchdogPid" -ErrorAction SilentlyContinue
    if ($null -ne $taskProcess -and $taskProcess.CommandLine -like '*start_backends.ps1*') {
        Stop-Process -Id $taskWatchdogPid -Force -ErrorAction SilentlyContinue
    }
}

# Child services can outlive the watchdog. Stop both port owners without
# deleting models, logs, or connection settings.
foreach ($taskPort in 38400,38401) {
    Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
}

for ($taskAttempt = 0; $taskAttempt -lt 20; $taskAttempt++) {
    $taskRemaining = @(foreach ($taskPort in 38400,38401) {
        Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue
    })
    if ($taskRemaining.Count -eq 0) { break }
    Start-Sleep -Milliseconds 250
}

Remove-Item -LiteralPath $taskPidFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $taskStatusFile -Force -ErrorAction SilentlyContinue
$taskStillListening = @(foreach ($taskPort in 38400,38401) {
    Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue
})
if ($taskStillListening.Count -gt 0) {
    Write-Error 'One or more backend ports are still listening.'
    exit 1
}
Write-Host 'Navigation backend (38400) stopped.'
Write-Host 'Color 3D backend (38401) stopped.'
Write-Host 'Models and logs were preserved.'
