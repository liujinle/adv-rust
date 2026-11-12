# bb-cli.ps1 - debug-only command line for the host app (com.adv.bb.app).
#
# Why: every guest-runtime change used to need a manual tour of
#   应用库 -> 详情 -> 安装 -> 实例页 -> 启动 -> 点隐私弹窗
# before `files/guest-trace.log` said anything. This script drives the same calls
# through a debug-only BroadcastReceiver (android/app/src/debug), so a fix can be
# verified with one line.
#
# Requires:
#   - adb on PATH and exactly one device/emulator
#   - a **debug** build installed (release has no DEBUG_CMD receiver)
#
# Examples:
#   .\tools\bb-cli.ps1 list
#   .\tools\bb-cli.ps1 launch 红果
#   .\tools\bb-cli.ps1 trace 60
#   .\tools\bb-cli.ps1 deploy            # build + install + (optionally) launch

param(
    [Parameter(Position = 0)][string]$Command = "help",
    [Parameter(Position = 1)][string]$Arg,
    [Parameter(Position = 2)][string]$Arg2
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$Package = "com.adv.bb.app"
$Receiver = "$Package/com.adv.bb.app.debug.DebugCommandReceiver"
$Action = "com.adv.bb.DEBUG_CMD"
$OutFile = "files/cli-out.txt"
$TraceFile = "files/guest-trace*.log"
$RepoRoot = Split-Path -Parent $PSScriptRoot

function Assert-Adb {
    # 不能要求行尾恰好是 "device"：部分 ROM / adb 版本会输出
    # "<serial>  device product:... model:... transport_id:N"，这时 \sdevice$ 匹配不到，
    # 会被误报成"没有连接的设备"。只要状态列是 device 即可（unauthorized/offline 不算）。
    $devices = & adb devices 2>$null | Where-Object { $_ -match '^\S+\s+device(\s|$)' }
    if (-not $devices) { throw "没有连接的设备（adb devices 为空）" }
}

function To-B64([string]$text) {
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
}

function Read-Out {
    $text = & adb shell "run-as $Package sh -c 'cat $OutFile'" 2>$null
    ($text -join "`n").Trim()
}

function Wait-Out([int]$timeoutSec = 25) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $hit = & adb shell "run-as $Package sh -c '[ -f $OutFile ] && echo 1'" 2>$null
        if (($hit -join "") -match "1") { return }
        Start-Sleep -Milliseconds 250
    }
}

# 长任务（导入 200 MB+ 的 APK）：结果文件先写"进行中 …"，等到出现 OK/ERR 才收工。
function Wait-Final([int]$timeoutSec = 300) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $out = Read-Out
        if ($out -match '^(OK|ERR)') { return $out }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

# `adb install -r` 之后应用处于 stopped 状态，系统会丢弃发给它的广播；
# 先由 shell 启动一次 DebugLaunchActivity（ping）把进程唤醒并解除 stopped。
function Ensure-Process {
    $running = (& adb shell pidof $Package 2>$null) -join ""
    if ($running.Trim()) { return }
    $ping = @(
        "shell", "am", "start", "-W",
        "-n", "$Package/.debug.DebugLaunchActivity",
        "--es", "selector_b64", (To-B64 "__ping__")
    )
    & adb @ping 2>&1 | Out-Null
    Start-Sleep -Milliseconds 800
}

# Sends one command through the debug receiver and prints what it wrote.
function Invoke-DebugCommand([string]$cmd, [string]$arg) {
    Assert-Adb
    Ensure-Process
    & adb shell "run-as $Package rm -f $OutFile" 2>$null | Out-Null

    # 显式组件 + --include-stopped-packages：`adb install -r` 之后应用处于
    # stopped 状态，普通广播会被系统静默丢弃（现象：result=0、没有 cli-out.txt）。
    $adbArgs = @(
        "shell", "am", "broadcast",
        "-a", $Action, "-p", $Package, "-n", $Receiver,
        "--include-stopped-packages",
        "--es", "cmd", $cmd
    )
    if ($arg) { $adbArgs += @("--es", "arg_b64", (To-B64 $arg)) }
    $sent = & adb @adbArgs 2>&1
    if ($sent -match "Error|Exception") { Write-Host $sent -ForegroundColor Red }

    Wait-Out
    $out = Read-Out
    if (-not $out) { Write-Host "（没有输出：广播被丢了吗？应用是否装的是 debug 包？）" -ForegroundColor Yellow; return }
    Write-Host $out
}

# 安装 debug 包。
#
# vivo / OriginOS 会拦一道「已了解应用的风险检测结果」确认框，不点就直接
# `INSTALL_FAILED_ABORTED: User rejected permissions`（`pm install` 也一样）。
# 普通设备走快路径；只有撞到这个错误才改成"后台安装 + 轮询点确认"。
function Install-Apk([string]$apkPath) {
    Assert-Adb
    $first = & adb install -r $apkPath 2>&1
    $first | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -eq 0) { return }
    $failedText = $first -join "`n"
    if ($failedText -notmatch "INSTALL_FAILED_ABORTED|User rejected permissions") {
        throw "adb install 失败：$failedText"
    }
    # vivo / OriginOS 锁屏时根本不弹确认框：安装会话直接被判为"用户拒绝"，
    # 继续轮询点确认只会白等 90 秒，还留下一个挂着的后台 adb install 进程。
    $wm = (& adb shell "dumpsys window" 2>&1) -join "`n"
    if ($wm -match "isKeyguardShowing=true|mDreamingLockscreen=true") {
        throw "设备处于锁屏状态：vivo 在锁屏下不弹安装确认框，会直接返回 User rejected permissions。请先手动解锁屏幕，再重跑 deploy。"
    }

    Write-Host "检测到系统安装确认框，自动点确认后重装…" -ForegroundColor Yellow
    # 固定文件名会被上一次没退出的后台 adb install 占住（vivo 确认框不点，
    # 进程就一直挂着），Remove-Item 会抛"文件正由另一进程使用"并中断整个
    # deploy。每次用唯一名，删不掉也不影响本次安装。
    $log = Join-Path ([IO.Path]::GetTempPath()) "bb-install-out-$PID-$(Get-Random -Maximum 99999).txt"
    $errLog = "$log.err"
    $proc = Start-Process -FilePath "adb" -ArgumentList @("install", "-r", $apkPath) `
        -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $errLog
    $deadline = (Get-Date).AddSeconds(90)
    $tapped = @{}
    while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 600
        # 每次先删旧文件：uiautomator dump 偶发失败时 cat 到上一轮 XML，
        # 会误以为确认框还在并反复点旧坐标（同一坑见 Get-UiXml）。
        $dump = (& adb shell "rm -f /sdcard/bb-install-ui.xml; uiautomator dump /sdcard/bb-install-ui.xml" 2>&1) -join "`n"
        if ($dump -notmatch "dumped to") { continue }
        $xml = (& adb exec-out cat /sdcard/bb-install-ui.xml) -join ""
        if ($xml -notmatch "<hierarchy") { continue }
        # vivo 的「已了解应用的风险检测结果」可能是 content-desc，且可点节点
        # 未必带 clickable="true"；只按文本 + bounds 取坐标。
        $hits = [regex]::Matches(
            $xml,
            '<node[^>]*(?:text|content-desc)="([^"]*(?:风险检测结果|已了解|仍要安装|继续安装|允许安装|确定安装)[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        )
        foreach ($hit in $hits) {
            $x = [int](([int]$hit.Groups[2].Value + [int]$hit.Groups[4].Value) / 2)
            $y = [int](([int]$hit.Groups[3].Value + [int]$hit.Groups[5].Value) / 2)
            $key = "$x,$y"
            if ($tapped.ContainsKey($key)) { continue }
            $tapped[$key] = $true
            Write-Host "点击安装确认：$($hit.Groups[1].Value) @ $key" -ForegroundColor Yellow
            & adb shell "input tap $x $y" 2>&1 | Out-Null
        }
    }
    if (-not $proc.HasExited) { $proc.WaitForExit(5000) | Out-Null }
    if (-not $proc.HasExited) {
        # 确认框没点上时别把后台安装进程留到下一次 deploy，否则 adb 安装
        # 会话和临时文件都会被它占着。
        try { $proc.Kill() } catch { }
        $proc.WaitForExit(2000) | Out-Null
    }
    $installOut = @(Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue)
    $installErr = @(Get-Content $errLog -Encoding UTF8 -ErrorAction SilentlyContinue)
    try { Remove-Item -Force $log, $errLog -ErrorAction SilentlyContinue } catch { }
    if (-not ($installOut -match "Success")) {
        throw "adb install 失败：$($installErr -join ' ')"
    }
    Write-Host "Success"
}

function Build-And-Install([switch]$SkipBuild) {
    if (-not $SkipBuild) {
        Push-Location (Join-Path $RepoRoot "android")
        try {
            & .\gradlew.bat :app:assembleDebug --offline
            if ($LASTEXITCODE -ne 0) { throw "gradle 构建失败" }
        } finally { Pop-Location }
    }
    $apk = Join-Path $RepoRoot "android/app/build/outputs/apk/debug/app-debug.apk"
    Install-Apk $apk
}

# vivo/OriginOS 上 `uiautomator dump` 偶发返回 "null root node" 或
# "could not get idle state"；不删远端旧文件就会 pull 到上一次的 XML，
# 把「首页网络出错」误读成上一页的剧集列表。每次重试都从干净状态开始。
function Get-UiXml {
    Assert-Adb
    $remote = "/sdcard/bb-cli-ui.xml"
    $tmp = Join-Path ([IO.Path]::GetTempPath()) "bb-cli-ui.xml"
    for ($i = 0; $i -lt 5; $i++) {
        & adb shell "rm -f $remote" 2>$null | Out-Null
        Remove-Item -Force $tmp -ErrorAction SilentlyContinue
        $dump = (& adb shell "uiautomator dump $remote" 2>&1) -join "`n"
        if ($dump -match "dumped to") {
            & adb pull $remote $tmp 2>$null | Out-Null
            if (Test-Path $tmp) {
                $xml = Get-Content -Raw -Encoding utf8 $tmp
                if ($xml -match "<hierarchy") { return $xml }
            }
        }
        Start-Sleep -Milliseconds 600
    }
    throw "uiautomator dump 失败：重试 5 次仍拿不到 UI 层级"
}

function Show-Ui {
    $xml = Get-UiXml
    if ($Arg) {
        [regex]::Matches($xml, '<node[^>]*?text="([^"]{1,40})"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') |
            Where-Object { $_.Groups[1].Value -match $Arg } |
            ForEach-Object { "$($_.Groups[1].Value)  $($_.Groups[2..5].Value -join ',')" }
    } else {
        [regex]::Matches($xml, 'text="([^"]{1,40})"') |
            ForEach-Object { $_.Groups[1].Value } |
            Select-Object -Unique
    }
}

switch ($Command.ToLower()) {
    "help" {
        Write-Host @"
用法: .\tools\bb-cli.ps1 <命令> [参数]

  list | ls            实例列表（id / 实例名 / #短id / 版本 / 包名 / 状态）
  rename <选择器> <新名称>  重命名实例，同包同版本时给两个实例起不同的名字
  apps                 已导入的 APK
  import <apk路径>      导入 APK 并建实例（等价 UI 的选文件导入；路径须宿主读得到）
  state                引擎状态
  procs                宿主与 P0~P3 槽位进程视图（pid / 归属实例）
  killguest [选择器]    只杀对应槽位进程与任务卡、不动账本；不传选择器=全部槽位
  toast <选择器> [文本] 在指定实例的槽位触发一次 Toast，验证隔离与按实例日志
  launch <选择器>       启动实例（id 前缀 / 包名 / 名称片段，支持中文）
  stop <选择器>         停止实例
  cleardata <选择器>    清除实例数据（虚拟目录清空重建，实例保留，等价 UI 的「清数据」）
  rm <选择器>           卸载实例（数据目录 + 备份 + 操作记录一起删，等价 UI 的「卸载」）
  trace [n]            聚合所有 guest-trace*.log 末 n 行（默认 120，直接读文件）
  launchtime           启动到进首页的 LAUNCH 分段耗时与总耗时（直接读 trace）
  cleartrace           清空所有 guest-trace*.log
  deploy               编译 + 安装 debug 包
  build                只编译
  install <apk路径>     安装指定 APK
  ui [正则]             dump 当前界面文本（可过滤）
  text <文本> tap       点击包含该文本的控件（等价于手点）
  shot [文件名]         截图
  logcat [n]           最近 n 行 logcat（含 BbCli 标记）
  help                 本帮助

前置：debug 变体（release 没有调试接收器）、adb 已连设备。
"@
    }
    "list" { Invoke-DebugCommand "list" $Arg }
    "ls" { Invoke-DebugCommand "list" $Arg }
    "rename" {
        if (-not $Arg -or -not $Arg2) { throw "rename 需要 <选择器> <新名称>" }
        Invoke-DebugCommand "rename" "$Arg|$Arg2"
    }
    "apps" { Invoke-DebugCommand "apps" $Arg }
    "state" { Invoke-DebugCommand "state" $Arg }
    "procs" { Invoke-DebugCommand "procs" $Arg }
    "killguest" { Invoke-DebugCommand "killguest" $Arg }
    "toast" {
        if (-not $Arg) { throw "toast 需要一个选择器：实例 id / 包名 / 名称片段" }
        $payload = if ($Arg2) { "$Arg|$Arg2" } else { $Arg }
        Invoke-DebugCommand "toast" $payload
    }
    "launch" {
        if (-not $Arg) { throw "launch 需要一个选择器：实例 id / 包名 / 名称片段" }
        # 走 DebugLaunchActivity 而不是广播：Android 10+ 禁止后台进程启动
        # Activity，广播到达时宿主多半在桌面，startActivity 会被系统静默丢弃。
        Assert-Adb
        & adb shell "run-as $Package rm -f $OutFile" 2>$null | Out-Null
        $adbArgs = @(
            "shell", "am", "start", "-W",
            "-n", "$Package/.debug.DebugLaunchActivity",
            "--es", "selector_b64", (To-B64 $Arg)
        )
        $sent = & adb @adbArgs 2>&1
        if ($sent -match "Error|Exception") { Write-Host ($sent -join "`n") -ForegroundColor Red }
        Wait-Out 30
        $out = Read-Out
        if ($out) { Write-Host $out }
        else { Write-Host "（没有输出：看 .\tools\bb-cli.ps1 logcat 与 trace）" -ForegroundColor Yellow }
    }
    "stop" {
        if (-not $Arg) { throw "stop 需要一个选择器：实例 id / 包名 / 名称片段" }
        Invoke-DebugCommand "stop" $Arg
    }
    "cleardata" {
        if (-not $Arg) { throw "cleardata 需要一个选择器：实例 id / 包名 / 名称片段" }
        Invoke-DebugCommand "cleardata" $Arg
    }
    "dismiss" { Invoke-DebugCommand "dismiss" }
    "factory" {
        if (-not $Arg) { throw "factory 需要一个 guest 类名：factory to0.c" }
        Invoke-DebugCommand "factory" $Arg
    }
    "methods" {
        if (-not $Arg) { throw "methods 需要一个 guest 类名：methods xt0.a" }
        Invoke-DebugCommand "methods" $Arg
    }
    "rm" {
        if (-not $Arg) { throw "rm 需要一个选择器：实例 id / 包名 / 名称片段" }
        Invoke-DebugCommand "rm" $Arg
    }
    "import" {
        if (-not $Arg) { throw "import 需要 APK 路径（宿主进程读得到的绝对路径）" }
        Assert-Adb
        Ensure-Process
        & adb shell "run-as $Package rm -f $OutFile" 2>$null | Out-Null
        $sent = & adb @(
            "shell", "am", "broadcast",
            "-a", $Action, "-p", $Package, "-n", $Receiver,
            "--include-stopped-packages",
            "--es", "cmd", "import",
            "--es", "arg_b64", (To-B64 $Arg)
        ) 2>&1
        if ($sent -match "Error|Exception") { Write-Host ($sent -join "`n") -ForegroundColor Red }
        $out = Wait-Final 300
        if ($out) { Write-Host $out }
        else { Write-Host "（导入超时/没回显：看 .\tools\bb-cli.ps1 logcat 与 trace）" -ForegroundColor Yellow }
    }
    "trace" {
        Assert-Adb
        $n = if ($Arg) { [int]$Arg } else { 120 }
        $files = (& adb shell "run-as $Package sh -c 'ls $TraceFile 2>/dev/null'" 2>$null) -join ""
        if (-not $files.Trim()) {
            Write-Host "（还没有 guest-trace*.log）"
        } else {
            & adb shell "run-as $Package sh -c 'wc -l $TraceFile; tail -n $n $TraceFile'"
        }
    }
    "launchtime" {
        Assert-Adb
        $raw = & adb shell "run-as $Package sh -c 'cat $TraceFile 2>/dev/null'" 2>$null
        $hits = @($raw | Where-Object { $_ -match '^\d+\s+\[[^\]]+\]\s+LAUNCH ' })
        if ($hits.Count -eq 0) {
            Write-Host "（没有 LAUNCH 计时行：先 cleartrace → launch）" -ForegroundColor Yellow
        } else {
            $parsed = @($hits | ForEach-Object {
                $line = $_
                $epoch = [long](($line -split '\s+', 2)[0])
                [pscustomobject]@{ T = $epoch; Line = $line }
            } | Sort-Object T)
            $t0 = $parsed[0].T
            foreach ($p in $parsed) {
                Write-Host ("+{0,6}ms  {1}" -f ($p.T - $t0), $p.Line)
            }
            $done = @($parsed | Where-Object { $_.Line -match 'DONE total=' } | Select-Object -Last 1)
            if ($done.Count -gt 0) {
                $m = [regex]::Match($done[0].Line, 'total=(\d+)ms')
                if ($m.Success) {
                    Write-Host ("LAUNCH_TOTAL={0}ms（启动 → 首页 onResume）" -f $m.Groups[1].Value) -ForegroundColor Green
                }
            } else {
                Write-Host "（本轮还没到首页：没有 LAUNCH DONE 行）" -ForegroundColor Yellow
            }
        }
    }
    "cleartrace" { Invoke-DebugCommand "cleartrace" $null }
    "classes" {
        if (-not $Arg) { throw "classes 需要一个类名片段（正则）：TTVideo / yy7 / videoshop" }
        Invoke-DebugCommand "classes" $Arg
    }
    "build" { Build-And-Install -SkipBuild:$false; Write-Host "已安装 debug 包" }
    "deploy" {
        Build-And-Install
        Write-Host "已安装 debug 包"
        if ($Arg) { Invoke-DebugCommand "launch" $Arg }
    }
    "install" {
        if (-not $Arg) { throw "install 需要 APK 路径" }
        Install-Apk $Arg
    }
    "ui" { Show-Ui }
    "text" {
        if (-not $Arg) { throw "text 需要要点击的文本" }
        $xml = Get-UiXml
        $hits = [regex]::Matches($xml, '<node[^>]*?text="' + [regex]::Escape($Arg) + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($hits.Count -eq 0) { throw "界面上没有文本：$Arg" }
        $candidates = @()
        foreach ($hit in $hits) {
            $cx = ([int]$hit.Groups[1].Value + [int]$hit.Groups[3].Value) / 2
            $cy = ([int]$hit.Groups[2].Value + [int]$hit.Groups[4].Value) / 2
            $candidates += [pscustomobject]@{ X = [int]$cx; Y = [int]$cy }
        }
        # 同一文本常有多处，典型是顶部搜索框 hint 和下方内容卡片同文。旧逻辑取
        # 第一个匹配，结果点进搜索页。默认改成取最靠下的内容区节点；要精确指定
        # 就传第二个参数当索引（0 = 最靠上，按 y 升序）。
        $ordered = @($candidates | Sort-Object -Property Y)
        if ($ordered.Count -gt 1) {
            Write-Host ("文本 '$Arg' 命中 {0} 处：{1}" -f $ordered.Count, (($ordered | ForEach-Object { "($($_.X),$($_.Y))" }) -join ' ')) -ForegroundColor DarkGray
        }
        $pick = $ordered[-1]
        if ($Arg2) {
            $idx = 0
            if (-not [int]::TryParse($Arg2, [ref]$idx)) { throw "第二个参数必须是候选索引：$Arg2" }
            if ($idx -lt 0 -or $idx -ge $ordered.Count) { throw "索引越界：$idx（共 $($ordered.Count) 处）" }
            $pick = $ordered[$idx]
        }
        & adb shell input touchscreen tap $pick.X $pick.Y
        Write-Host "点击 $Arg @ $($pick.X),$($pick.Y)"
    }
    "shot" {
        Assert-Adb
        $name = if ($Arg) { $Arg } else { "screenshot.png" }
        & adb exec-out screencap -p > $name
        Write-Host "已保存 $name"
    }
    "logcat" {
        Assert-Adb
        $n = if ($Arg) { [int]$Arg } else { 200 }
        & adb logcat -d -t $n 2>&1 | Select-String -Pattern "BbCli|GuestRuntime|GuestLauncher|AndroidRuntime|FATAL"
    }
    default { Write-Host "未知命令：$Command" -ForegroundColor Yellow; & $PSCommandPath help }
}
