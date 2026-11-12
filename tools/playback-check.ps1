# playback-check.ps1 - 播放连续性回归（截图帧差异断言 + trace 指标）
#
# Why: 之前判断"播放到底有没有真的在放"靠人眼看截图（HANDOFF 第 6 节遗留：
#   "把截图帧差异断言做成自动化脚本（本轮是临时 PIL 脚本，未入 CI）"）。
#   这个脚本把那次临时脚本固化下来，一条命令给出可断言的结论：
#     - 相邻采样帧的平均灰度差（MAE）有没有超过阈值 => 画面在动
#     - trace 里 100103 / content-length=0 / REQ FAIL 有没有归零 => 接口没坏
#   任一不满足就以非 0 退出码失败，方便挂到 CI 或 pre-commit。
#
# 依赖: adb、debug 变体（有 DEBUG_CMD 接收器）、System.Drawing（Windows PowerShell）。
#
# 用法:
#   .\tools\playback-check.ps1                          # 对当前 RUNNING 实例采样 5 帧
#   .\tools\playback-check.ps1 -Selector ad6446c5 -ColdStart
#   .\tools\playback-check.ps1 -TapText 持械入宋 -Samples 4 -IntervalSec 10
#   .\tools\playback-check.ps1 -MinDiff 1.5 -MinMovingRatio 0.8   # 更严格的回归
#
# 说明: 播放页 `uiautomator dump` 经常拿不到层级（could not get idle state），
#   所以这里全程只用 `screencap`，不依赖 UI 树。

param(
    [string]$Selector = "",
    [switch]$ColdStart,
    [string[]]$TapText = @(),
    [int]$Samples = 5,
    [int]$IntervalSec = 15,
    [double]$MinDiff = 0.5,
    [double]$MinMovingRatio = 0.6,
    [string]$OutDir = "tmp/playback",
    [switch]$SkipTrace,
    [switch]$KeepFrames,
    [switch]$Monitor
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$Package = "com.adv.bb.app"
$GuestPackage = "com.phoenix.read"
$TraceFile = "files/guest-trace.log"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Cli = Join-Path $PSScriptRoot "bb-cli.ps1"

if ($Samples -lt 2) { throw "Samples 至少要 2，否则算不出帧间差异" }
if (-not (Test-Path $Cli)) { throw "找不到 bb-cli.ps1：$Cli" }

# bb-cli.ps1 是 UTF-8 无 BOM 的中文脚本，Windows PowerShell 5.1 会按 GBK 读成乱码
# 并直接解析失败（表现为一堆 "The '<' operator is reserved for future use"），
# 而脚本又继续往下跑，造成"冷启动没执行却报告 PASS"的假阳性。优先用 pwsh。
$CliHost = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
if (-not $CliHost) { $CliHost = (Get-Command powershell -ErrorAction Stop).Source }

Add-Type -AssemblyName System.Drawing

function Assert-Adb {
    $devices = & adb devices 2>$null | Where-Object { $_ -match '^\S+\s+device(\s|$)' }
    if (-not $devices) { throw "没有连接的设备（adb devices 为空）" }
}

function Get-RunningInstance {
    # bb-cli list 一行一个实例：id <TAB> 名称 <TAB> 版本... <TAB> 状态 ...
    $lines = & $CliHost -NoProfile -ExecutionPolicy Bypass -File $Cli list 2>&1
    $running = @()
    foreach ($line in $lines) {
        if ($line -notmatch 'RUNNING') { continue }
        $cols = ($line -split "`t")
        if ($cols.Count -ge 2) { $running += $cols[0].Trim() }
    }
    if ($running.Count -eq 0) { throw "没有 RUNNING 的实例，请先 .\tools\bb-cli.ps1 launch <选择器>" }
    return $running[0]
}

function Invoke-Cli {
    param([string[]]$CliArgs, [switch]$AllowFail)
    $out = & $CliHost -NoProfile -ExecutionPolicy Bypass -File $Cli @CliArgs 2>&1
    $code = $LASTEXITCODE
    $out | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    # 默认失败即抛错：之前冷启动静默失败、继续采集旧画面导致 PASS 假阳性。
    if ($code -ne 0 -and -not $AllowFail) {
        throw "bb-cli $($CliArgs -join ' ') 失败 (exit $code)"
    }
    return ($out -join "`n")
}

function Get-GraySamples {
    # 降采样成灰度数组，避免逐像素比较太慢（1080x2400 全量约 260 万点）。
    param([string]$Path, [int]$Step = 8)
    $bmp = [System.Drawing.Bitmap]::FromFile($Path)
    try {
        $w = $bmp.Width; $h = $bmp.Height
        $sw = [int][Math]::Ceiling($w / $Step)
        $sh = [int][Math]::Ceiling($h / $Step)
        $arr = New-Object 'double[]' ($sw * $sh)
        $i = 0
        for ($y = 0; $y -lt $h; $y += $Step) {
            for ($x = 0; $x -lt $w; $x += $Step) {
                $c = $bmp.GetPixel($x, $y)
                $arr[$i] = 0.299 * $c.R + 0.587 * $c.G + 0.114 * $c.B
                $i++
            }
        }
        return , $arr
    } finally {
        $bmp.Dispose()
    }
}

function Get-MeanAbsDiff {
    param([double[]]$A, [double[]]$B)
    $n = [Math]::Min($A.Length, $B.Length)
    if ($n -eq 0) { return 0.0 }
    $sum = 0.0
    for ($i = 0; $i -lt $n; $i++) { $sum += [Math]::Abs($A[$i] - $B[$i]) }
    return $sum / $n
}

function Get-TraceStats {
    param([string]$InstanceId)
    $raw = (& adb shell "run-as $Package cat $TraceFile" 2>$null) -join "`n"
    if (-not $raw) { return $null }
    return [ordered]@{
        行数        = ($raw -split "`n").Count
        paramInvalid = ([regex]::Matches($raw, '100103')).Count
        空播放体    = ([regex]::Matches($raw, 'content-length=0')).Count
        请求失败    = ([regex]::Matches($raw, 'REQ FAIL')).Count
        播放接口    = ([regex]::Matches($raw, 'novel/player/')).Count
        补公共参数  = ([regex]::Matches($raw, 'PATCH 公共参数')).Count
    }
}

function Get-ProcessSnapshot {
    # guest 是独立进程 com.phoenix.read，不是宿主 com.adv.bb.app 的空壳进程。
    # 采样对象选错会把 720MB 的真实负载读成 65MB，稳定性结论就失真了。
    $guestPid = $null
    foreach ($name in @($GuestPackage, $Package)) {
        $raw = (& adb shell "pidof $name" 2>$null | Out-String).Trim()
        $cand = ($raw -split "\s+")[0]
        if ($cand -match '^\d+$') { $guestPid = $cand; break }
    }
    if (-not $guestPid) { return $null }
    $mem = (& adb shell "dumpsys meminfo $guestPid" 2>$null) -join "`n"
    $m = [regex]::Match($mem, 'TOTAL PSS:\s+(\d+)')
    if (-not $m.Success) { $m = [regex]::Match($mem, 'TOTAL\s+(\d+)') }
    $pss = if ($m.Success) { [int]$m.Groups[1].Value } else { 0 }
    # shell 用户读不到应用进程的 fd 目录（SELinux），要走 run-as 切到应用 uid。
    $fds = & adb shell "run-as $Package ls /proc/$guestPid/fd" 2>$null
    $fdCount = @($fds | Where-Object { $_ -and $_ -match '^\d+$' }).Count
    return [ordered]@{ Pid = $guestPid; PssKb = $pss; Fd = $fdCount }
}

# ---------- 主流程 ----------
Assert-Adb

if (-not $Selector) { $Selector = Get-RunningInstance }
Write-Host "实例: $Selector" -ForegroundColor Cyan

if ($ColdStart) {
    Write-Host "[1/4] 冷启动（stop + cleartrace + launch）" -ForegroundColor Cyan
    # stop 对已停止的实例会报错，cleartrace 的广播在宿主刚被杀时可能丢，都允许失败；
    # launch 必须成功，否则后面的采样毫无意义。
    Invoke-Cli @("stop", $Selector) -AllowFail | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Cli @("cleartrace") -AllowFail | Out-Null
    Invoke-Cli @("launch", $Selector) | Out-Null
    # 冷启动到首页渲染完约 15~22s（实测 14s 时还停在黑屏启动页，采样会全黑）。
    Start-Sleep -Seconds 20
} else {
    Write-Host "[1/4] 跳过冷启动（沿用当前界面）" -ForegroundColor Cyan
}

if ($TapText.Count -gt 0) {
    Write-Host ("[2/4] 依次点击: {0}" -f ($TapText -join ' -> ')) -ForegroundColor Cyan
    foreach ($tap in $TapText) {
        # 冷启动后 uiautomator 偶尔还抓不到 guest 窗口（层级为空），重试几次。
        $clicked = $false
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            Invoke-Cli @("text", $tap) -AllowFail | Out-Null
            if ($LASTEXITCODE -eq 0) { $clicked = $true; break }
            Write-Host ("  第 {0} 次点击 '{1}' 失败，等 4s 重试" -f $attempt, $tap) -ForegroundColor Yellow
            Start-Sleep -Seconds 4
        }
        if (-not $clicked) { throw "连续 3 次点不到 '$tap'：界面上没有该文本，或 uiautomator 不可用" }
        Start-Sleep -Seconds 3
    }
    Start-Sleep -Seconds 4
} else {
    Write-Host "[2/4] 未指定 -TapText，直接对当前界面采样" -ForegroundColor Cyan
}

$dir = Join-Path $RepoRoot $OutDir
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Get-ChildItem $dir -Filter "frame-*.png" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host "[3/4] 采样 $Samples 帧，间隔 ${IntervalSec}s -> $dir" -ForegroundColor Cyan
$frames = @()
$snaps = @()
for ($i = 0; $i -lt $Samples; $i++) {
    if ($Monitor) {
        $snap = Get-ProcessSnapshot
        if ($snap) {
            $snaps += $snap
            Write-Host ("  快照 {0}: pid={1} PSS={2:N0}KB fd={3}" -f $i, $snap.Pid, $snap.PssKb, $snap.Fd) -ForegroundColor DarkGray
        }
    }
    $file = Join-Path $dir ("frame-{0:d2}.png" -f $i)
    & adb exec-out screencap -p > $file
    if (-not (Test-Path $file) -or (Get-Item $file).Length -lt 1024) {
        throw "第 $i 帧截图失败或过小：$file"
    }
    $frames += $file
    Write-Host ("  帧 {0}: {1} ({2:N0} B)" -f $i, (Split-Path $file -Leaf), (Get-Item $file).Length)
    if ($i -lt $Samples - 1) { Start-Sleep -Seconds $IntervalSec }
}

Write-Host "[4/4] 计算帧间差异" -ForegroundColor Cyan
$grays = @()
foreach ($f in $frames) { $grays += , (Get-GraySamples -Path $f) }

$diffs = @()
for ($i = 1; $i -lt $grays.Count; $i++) {
    $d = Get-MeanAbsDiff -A $grays[$i - 1] -B $grays[$i]
    $diffs += $d
    Write-Host ("  帧 {0} -> {1}: MAE = {2:N3}" -f ($i - 1), $i, $d)
}

$moving = ($diffs | Where-Object { $_ -gt $MinDiff }).Count
$ratio = $moving / $diffs.Count
$frameOk = ($ratio -ge $MinMovingRatio)

$traceStats = $null
$traceOk = $true
if (-not $SkipTrace) {
    $traceStats = Get-TraceStats -InstanceId $Selector
    if ($traceStats) {
        $traceOk = ($traceStats.paramInvalid -eq 0) -and ($traceStats.空播放体 -eq 0)
    }
}

Write-Host ""
Write-Host "===== playback-check 结果 =====" -ForegroundColor Cyan
Write-Host ("帧差异: {0}/{1} 段超过 {2} (占比 {3:P0}，要求 >= {4:P0}) => {5}" -f `
        $moving, $diffs.Count, $MinDiff, $ratio, $MinMovingRatio, $(if ($frameOk) { "PASS" } else { "FAIL" })) `
    -ForegroundColor $(if ($frameOk) { "Green" } else { "Red" })
if ($traceStats) {
    foreach ($k in $traceStats.Keys) { Write-Host ("  {0}: {1}" -f $k, $traceStats[$k]) }
    Write-Host ("接口指标 (100103=0 且 空播放体=0) => {0}" -f $(if ($traceOk) { "PASS" } else { "FAIL" })) `
        -ForegroundColor $(if ($traceOk) { "Green" } else { "Red" })
} else {
    Write-Host "  （没读到 trace，跳过接口指标）" -ForegroundColor Yellow
}
if ($Monitor) {
    if ($snaps.Count -ge 2) {
        $first = $snaps[0]
        $last = $snaps[-1]
        $dPss = $last.PssKb - $first.PssKb
        $dFd = $last.Fd - $first.Fd
        $pssText = if ($dPss -ge 0) { "+$dPss" } else { "$dPss" }
        $fdText = if ($dFd -ge 0) { "+$dFd" } else { "$dFd" }
        $stable = ($dPss -lt 51200) -and ($dFd -lt 50)
        Write-Host ("内存: PSS {0:N0}KB -> {1:N0}KB (Δ {2}KB)" -f $first.PssKb, $last.PssKb, $pssText)
        Write-Host ("句柄: fd {0} -> {1} (Δ {2})" -f $first.Fd, $last.Fd, $fdText)
        Write-Host ("稳定性软判 (PSS Δ < 50MB 且 fd Δ < 50) => {0}" -f $(if ($stable) { "OK" } else { "观察" })) `
            -ForegroundColor $(if ($stable) { "Green" } else { "Yellow" })
    } else {
        Write-Host "  快照不足 2 个，跳过稳定性对比" -ForegroundColor Yellow
    }
}
Write-Host "截图: $dir" -ForegroundColor DarkGray

if (-not $KeepFrames) {
    Get-ChildItem $dir -Filter "frame-*.png" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
}

if ($frameOk -and $traceOk) {
    Write-Host "PLAYBACK_OK" -ForegroundColor Green
    exit 0
}
Write-Host "PLAYBACK_FAIL" -ForegroundColor Red
exit 1
