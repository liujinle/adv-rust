# Host JNI smoke test for the native core.
#
# Builds the cdylib, compiles the Java mirror of NativeBridge plus the smoke
# test, and runs it against a real JVM. Requires JDK 8+ (JDK 17 is fine).
#
#   pwsh -File tests/jni/run-smoke.ps1
#
# It is intentionally not part of `cargo test`: CI machines without a JDK can
# still run the Rust test suite.

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$nativeRoot = Join-Path $repoRoot "native"
$javaRoot = Join-Path $PSScriptRoot "java"
$outDir = Join-Path $PSScriptRoot "out"

Write-Host "==> building bb_native cdylib"
Push-Location $nativeRoot
try {
    cargo build -p bb-bridge --offline
} finally {
    Pop-Location
}

$libraryDir = Join-Path $nativeRoot "target\debug"
if (-not (Test-Path (Join-Path $libraryDir "bb_native.dll"))) {
    throw "bb_native.dll not found in $libraryDir"
}

if (Test-Path $outDir) {
    Remove-Item $outDir -Recurse -Force
}
New-Item -ItemType Directory -Path $outDir | Out-Null

Write-Host "==> compiling Java bridge + smoke test"
$sources = Get-ChildItem -Path $javaRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -d $outDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed"
}

Write-Host "==> running JNI smoke test"
& java "-Djava.library.path=$libraryDir" -cp $outDir com.adv.bb.nativebridge.BridgeSmokeTest
if ($LASTEXITCODE -ne 0) {
    throw "JNI smoke test failed"
}
