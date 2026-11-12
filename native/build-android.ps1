# Cross-compiles the native core for Android and stages the .so files where the
# Gradle module expects them.
#
# Prerequisites (network access required once):
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#   $env:ANDROID_NDK_HOME = "<sdk>\ndk\27.0.12077973"
#
# Usage:
#   pwsh -File native/build-android.ps1                 # release, all ABIs
#   pwsh -File native/build-android.ps1 -Profile debug  # dev profile
#   pwsh -File native/build-android.ps1 -Abis arm64-v8a # single ABI

param(
    [ValidateSet("debug", "release")]
    [string]$Profile = "release",
    [string[]]$Abis = @("arm64-v8a", "armeabi-v7a", "x86_64")
)

$ErrorActionPreference = "Stop"

$nativeRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $nativeRoot
$jniLibsRoot = Join-Path $repoRoot "android\bbcore\src\main\jniLibs"

$ndk = $env:ANDROID_NDK_HOME
if (-not $ndk) { $ndk = Join-Path $env:ANDROID_HOME "ndk\27.0.12077973" }
if (-not (Test-Path $ndk)) {
    throw "Android NDK not found. Set ANDROID_NDK_HOME (looked at '$ndk')."
}

$hostTag = if ($IsWindows) { "windows-x86_64" } elseif ($IsMacOS) { "darwin-x86_64" } else { "linux-x86_64" }
$clangSuffix = if ($IsWindows) { ".cmd" } else { "" }

$targets = @{
    "arm64-v8a"   = @{ triple = "aarch64-linux-android";    clang = "aarch64-linux-android21-clang" }
    "armeabi-v7a" = @{ triple = "armv7-linux-androideabi";  clang = "armv7a-linux-androideabi21-clang" }
    "x86_64"      = @{ triple = "x86_64-linux-android";     clang = "x86_64-linux-android21-clang" }
}

$cargoProfile = if ($Profile -eq "release") { "release" } else { "dev" }
$outputDir = if ($Profile -eq "release") { "release" } else { "debug" }

$installedTargets = (& rustup target list --installed) | ForEach-Object { $_.Trim() }

Push-Location $nativeRoot
try {
    foreach ($abi in $Abis) {
        if (-not $targets.ContainsKey($abi)) { throw "Unknown ABI '$abi'" }
        $target = $targets[$abi]
        $triple = $target.triple

        if ($installedTargets -notcontains $triple) {
            throw "Rust target '$triple' is not installed. Run: rustup target add $triple"
        }

        $clangPath = Join-Path $ndk "toolchains\llvm\prebuilt\$hostTag\bin\$($target.clang)$clangSuffix"
        if (-not (Test-Path $clangPath)) { throw "NDK clang not found: $clangPath" }

        $linkerVar = "CARGO_TARGET_" + $triple.ToUpper().Replace("-", "_") + "_LINKER"
        Set-Item -Path "env:$linkerVar" -Value $clangPath
        Set-Item -Path "env:CC_$triple" -Value $clangPath
        Set-Item -Path "env:AR_$triple" -Value (Join-Path $ndk "toolchains\llvm\prebuilt\$hostTag\bin\llvm-ar")

        Write-Host "==> cargo build --profile $cargoProfile --target $triple"
        & cargo build -p bb-bridge --profile $cargoProfile --target $triple
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $triple" }

        $artifact = Join-Path $nativeRoot "target\$triple\$outputDir\libbb_native.so"
        if (-not (Test-Path $artifact)) { throw "missing artifact: $artifact" }

        $destination = Join-Path $jniLibsRoot $abi
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        Copy-Item $artifact (Join-Path $destination "libbb_native.so") -Force
        Write-Host "    staged $abi -> $destination"
    }
} finally {
    Pop-Location
}

Write-Host "done. Next: cd android; ./gradlew :bbcore:assembleDebug"
