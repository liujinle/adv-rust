plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}

// CI 与 tools/verify.ps1 共用的 JVM 单测入口；真机/模拟器测试不在这里。
tasks.register("verifyUnitTests") {
    group = "verification"
    description = "Run all non-instrumented Android unit tests."
    dependsOn(
        ":core:engine-api:testDebugUnitTest",
        ":core:database:testDebugUnitTest",
        ":core:data:testDebugUnitTest",
        ":app:testDebugUnitTest",
    )
}
