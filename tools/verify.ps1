param(
    [switch]$SkipRust,
    [switch]$SkipRelease,
    [switch]$RequireSigned
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$nativeDir = Join-Path $root 'native'
$androidDir = Join-Path $root 'android'

function Resolve-Jdk17 {
    $candidates = @(
        $env:JAVA_HOME,
        'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot',
        'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot',
        'C:\Program Files\Java\jdk-17',
        '/usr/lib/jvm/temurin-17-jdk-amd64'
    )
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $javaExe = Join-Path $candidate 'bin/java.exe'
        $javaBin = Join-Path $candidate 'bin/java'
        if ((Test-Path $javaExe) -or (Test-Path $javaBin)) { return $candidate }
    }
    throw '找不到 JDK 17；请设置 JAVA_HOME 后重试。'
}

function Invoke-Checked {
    param(
        [string]$Step,
        [scriptblock]$Action
    )
    Write-Host "== $Step =="
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Step 失败（exit=$LASTEXITCODE）"
    }
}

$env:JAVA_HOME = Resolve-Jdk17
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
if ($RequireSigned) {
    $env:RELEASE_SIGNING_REQUIRED = 'true'
} else {
    $env:RELEASE_SIGNING_REQUIRED = ''
}

if (-not $SkipRust) {
    Push-Location $nativeDir
    try {
        Invoke-Checked 'Rust workspace tests' { cargo test --workspace --offline }
    } finally {
        Pop-Location
    }
}

$gradleTasks = @('verifyUnitTests', ':app:assembleDebug')
if (-not $SkipRelease) { $gradleTasks += ':app:archiveRelease' }
Push-Location $androidDir
try {
    Invoke-Checked 'Android unit tests / debug APK / release archive' {
        & .\gradlew.bat --offline --no-daemon @gradleTasks
    }
} finally {
    Pop-Location
}

Write-Host "VERIFY_OK root=$root requireSigned=$RequireSigned"
