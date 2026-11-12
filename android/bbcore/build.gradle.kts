import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adv.bb"
    compileSdk = 36
    // Pinned so `libbb_native.so` and the Gradle build agree on the toolchain.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    sourceSets.getByName("main") {
        // Filled by :bbcore:assembleNative. Not wired into preBuild on purpose:
        // a machine without the Rust Android targets must still be able to run
        // unit tests and Kotlin compilation.
        jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// Rust integration
// ---------------------------------------------------------------------------

data class NativeTarget(val abi: String, val triple: String, val clang: String)

val nativeTargets = listOf(
    NativeTarget("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android21-clang"),
    NativeTarget("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi21-clang"),
    NativeTarget("x86_64", "x86_64-linux-android", "x86_64-linux-android21-clang"),
)

val androidRoot = rootProject.layout.projectDirectory
val nativeRoot = androidRoot.dir("../native")

val hostTag = run {
    val os = System.getProperty("os.name").lowercase()
    when {
        os.contains("windows") -> "windows-x86_64"
        os.contains("mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
}

val ndkDirectory: String = System.getenv("ANDROID_NDK_HOME")
    ?: "${System.getenv("ANDROID_HOME")}/ndk/27.0.12077973"

fun clangPath(target: NativeTarget): String {
    val suffix = if (hostTag == "windows-x86_64") ".cmd" else ""
    return "$ndkDirectory/toolchains/llvm/prebuilt/$hostTag/bin/${target.clang}$suffix"
}

// Android 15+ 要求 native 库的 LOAD 段按 16 KB 对齐（否则 16 KB 页设备上 dlopen
// 直接失败，4 KB 页设备也会弹「不符合 16 KB 对齐要求」）。NDK r27 的 clang 默认
// 仍是 4 KB，这里显式把 max/common page size 顶到 0x4000。
val pageSizeLinkArgs =
    "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"

val cargoTasks = nativeTargets.map { target ->
    tasks.register<Exec>("cargoBuild${target.abi.filter { it.isLetterOrDigit() }}") {
        group = "bb"
        description = "cargo build -p bb-bridge --release --target ${target.triple}"
        workingDir = nativeRoot.asFile
        commandLine("cargo", "build", "-p", "bb-bridge", "--release", "--target", target.triple)
        environment(
            "CARGO_TARGET_${target.triple.uppercase().replace('-', '_')}_LINKER",
            clangPath(target),
        )
        environment("CC_${target.triple}", clangPath(target))
        environment("AR_${target.triple}", "$ndkDirectory/toolchains/llvm/prebuilt/$hostTag/bin/llvm-ar")
        environment("RUSTFLAGS", listOfNotNull(System.getenv("RUSTFLAGS"), pageSizeLinkArgs).joinToString(" "))
    }
}

val copyNativeLibs = tasks.register<Copy>("copyNativeLibs") {
    group = "bb"
    description = "Copies libbb_native.so for every ABI into the jniLibs output dir."
    dependsOn(cargoTasks)
    nativeTargets.forEach { target ->
        from(nativeRoot.dir("target/${target.triple}/release")) {
            include("libbb_native.so")
            into(target.abi)
        }
    }
    into(layout.buildDirectory.dir("rustJniLibs"))
}

tasks.register("assembleNative") {
    group = "bb"
    description = "Builds and stages the Rust core for arm64-v8a, armeabi-v7a and x86_64."
    dependsOn(copyNativeLibs)
}
