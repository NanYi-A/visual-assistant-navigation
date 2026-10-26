param([switch]$Watchdog)
$ErrorActionPreference = 'Stop'

# 本脚本启动两个后端服务：
#   :online3d-server      导航后端（Kotlin/JVM，VA3D 协议服务端）→ 端口 38400
#   color_3d/server.py    彩色网格显示后端（Python）              → 端口 38401
#
# 所有外部依赖路径都从环境变量读取，避免把本机目录结构写死：
#   JAVA_HOME              JDK 路径（必需）
#   GRADLE_BIN             gradle 可执行文件路径（可选，默认用仓库自带的 gradlew.bat）
#   PYTHON_COLOR           彩色后端用的 Python 解释器（必需，需装 open3d / numpy）
#   RELOCALIZATION_PYTHON  重定位用的 Python（可选，默认同 PYTHON_COLOR）
#   LIGHTGLUE_PATH         LightGlue 权重目录（可选）
#   TORCH_HOME             torch 缓存目录（可选）

$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$taskOutput = Join-Path $PSScriptRoot 'output'
$taskState = Join-Path $taskOutput 'watchdog.json'
$taskPidFile = Join-Path $taskOutput 'watchdog.pid'
$taskPowerShell = (Get-Process -Id $PID).Path
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null

if (-not $Watchdog) {
    if (Test-Path -LiteralPath $taskPidFile) {
        $taskOldPid = [int](Get-Content -LiteralPath $taskPidFile -Raw)
        Stop-Process -Id $taskOldPid -Force -ErrorAction SilentlyContinue
    }
    foreach ($taskPort in 38400,38401) {
        Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue |
            ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    }
    $taskOldFiles = @(
        (Join-Path $taskRoot 'maps\home.vamap'),
        (Join-Path $taskRoot 'maps\home.vamap.bak'),
        (Join-Path $taskRoot 'maps\home.vamap.tmp'),
        (Join-Path $taskOutput 'latest_colored_mesh.ply'),
        (Join-Path $taskOutput 'latest_colored_mesh.tmp.ply')
    )
    foreach ($taskFile in $taskOldFiles) { if (Test-Path -LiteralPath $taskFile) { Remove-Item -LiteralPath $taskFile -Force } }
    # A fresh backend session must not reuse the active runtime map, but the user-facing
    # home model library is persistent data and must survive backend restarts.
    $taskGuard = Start-Process -FilePath $taskPowerShell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',$PSCommandPath,'-Watchdog' `
        -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru
    $taskGuard.Id | Set-Content -LiteralPath $taskPidFile -Encoding ascii
    # Gradle cold starts can take well over seven seconds. Wait until both services
    # are ready (up to 45 seconds) so a healthy navigation backend is not reported
    # as failed merely because it is still compiling/starting.
    $taskPorts = @()
    foreach ($taskAttempt in 1..90) {
        $taskPorts = @(Get-NetTCPConnection -State Listen -LocalPort 38400,38401 -ErrorAction SilentlyContinue | Select-Object LocalPort,OwningProcess)
        if ($taskPorts.LocalPort -contains 38400 -and $taskPorts.LocalPort -contains 38401) { break }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "Backend watchdog started, PID $($taskGuard.Id). Old home models were not reused."
    $taskPorts | Format-Table -AutoSize
    if ($taskPorts.LocalPort -notcontains 38400 -or $taskPorts.LocalPort -notcontains 38401) { throw 'Backends are not both listening. Check output\watchdog.log.' }
    exit 0
}

$PID | Set-Content -LiteralPath $taskPidFile -Encoding ascii

# ---- 依赖路径：全部走环境变量，缺失即给出明确提示 ----
if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME 未设置：请先指向 JDK，例如 $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"'
}
if (-not $env:PYTHON_COLOR) {
    throw 'PYTHON_COLOR 未设置：请指向安装了 open3d / numpy 的 Python 解释器'
}

$taskGradle = if ($env:GRADLE_BIN) { $env:GRADLE_BIN } else { Join-Path $taskRoot 'gradlew.bat' }
$taskPython = $env:PYTHON_COLOR
$taskLog = Join-Path $taskOutput 'watchdog.log'

if (-not $env:RELOCALIZATION_PYTHON) { $env:RELOCALIZATION_PYTHON = $taskPython }
if (-not $env:TORCH_HOME) { $env:TORCH_HOME = Join-Path $taskRoot 'models\torch_cache' }
$env:OPEN3D_CPU_RENDERING = 'true'

function Start-TaskBackend([string]$taskName) {
    $taskStamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    if ($taskName -eq 'navigation') {
        $taskExecutable = $taskGradle
        $taskHomeMap = Join-Path $taskRoot 'maps\home.vamap'
        $taskModelsRoot = Join-Path $taskRoot 'models'
        $taskArguments = ":online3d-server:run --args=`"--port 38400 --home-map $taskHomeMap --models $taskModelsRoot`""
    } else {
        $taskExecutable = $taskPython
        $taskArguments = 'tools\color_3d\server.py --port 38401 --voxel-cm 4 --output tools\color_3d\output --models models'
    }
    Start-Process -FilePath $taskExecutable -ArgumentList $taskArguments -WorkingDirectory $taskRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $taskOutput "$taskName-$taskStamp.log") `
        -RedirectStandardError (Join-Path $taskOutput "$taskName-$taskStamp.err.log") | Out-Null
    "$(Get-Date -Format o) started $taskName" | Add-Content -LiteralPath $taskLog -Encoding utf8
    $taskLastStart[$taskName] = Get-Date
}

$taskLastStart = @{ navigation = [datetime]::MinValue; color = [datetime]::MinValue }
while ($true) {
    $taskNavigation = Get-NetTCPConnection -State Listen -LocalPort 38400 -ErrorAction SilentlyContinue
    $taskColor = Get-NetTCPConnection -State Listen -LocalPort 38401 -ErrorAction SilentlyContinue
    $taskNow = Get-Date
    if (-not $taskNavigation -and ($taskNow - $taskLastStart.navigation).TotalSeconds -ge 20) { Start-TaskBackend 'navigation' }
    if (-not $taskColor -and ($taskNow - $taskLastStart.color).TotalSeconds -ge 20) { Start-TaskBackend 'color' }
    [ordered]@{
        watchdogPid = $PID
        checkedAt = (Get-Date).ToString('o')
        navigationListening = [bool](Get-NetTCPConnection -State Listen -LocalPort 38400 -ErrorAction SilentlyContinue)
        colorListening = [bool](Get-NetTCPConnection -State Listen -LocalPort 38401 -ErrorAction SilentlyContinue)
        reuseOldHomeModel = $false
    } | ConvertTo-Json | Set-Content -LiteralPath $taskState -Encoding utf8
    Start-Sleep -Seconds 5
}
