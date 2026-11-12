import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.adv.core.domain"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:engine-api"))
    // The domain layer orchestrates repositories rather than re-implementing
    // storage. Use cases take repository types in their constructors, so the
    // dependency is exposed.
    api(project(":core:data"))
    implementation(libs.kotlinx.coroutines.android)
}
