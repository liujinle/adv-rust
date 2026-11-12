import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 签名材料只从环境变量或 ~/.gradle/gradle.properties 读取，绝不入库。
val envOrProperty: (String) -> String? = { name ->
    providers.environmentVariable(name)
        .orElse(providers.gradleProperty(name))
        .orNull
        ?.takeIf { it.isNotBlank() }
}
val releaseKeystore = envOrProperty("RELEASE_KEYSTORE_FILE")
val releaseStorePassword = envOrProperty("RELEASE_KEYSTORE_PASSWORD")
val releaseAlias = envOrProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword = envOrProperty("RELEASE_KEY_PASSWORD") ?: releaseStorePassword
val releaseSigningReady =
    listOf(releaseKeystore, releaseStorePassword, releaseAlias, releaseKeyPassword)
        .all { !it.isNullOrBlank() } && releaseKeystore?.let { file(it).isFile } == true

android {
    namespace = "com.adv.bb.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adv.bb.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Only the crash probe is native; the device in use is arm64.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    if (releaseSigningReady) {
        signingConfigs {
            create("releaseExternal") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            // The client is a shell around the native engine; shrinking it is a
            // later optimisation, not something to guess at before it ships.
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("releaseExternal")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":bbcore"))
    implementation(project(":core:common"))
    implementation(project(":core:engine-api"))
    implementation(project(":core:engine-impl"))
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))

    implementation(project(":feature:home"))
    implementation(project(":feature:apps"))
    implementation(project(":feature:instances"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:diagnostics"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
}

val releaseVersionName = android.defaultConfig.versionName ?: "0.1.0"
val releaseVersionCode = android.defaultConfig.versionCode ?: 0
val releaseApplicationId = android.defaultConfig.applicationId ?: "com.adv.bb.app"
val releaseArchiveDir = layout.buildDirectory.dir("outputs/archive/adv-rust-$releaseVersionName-release")

// 归档同时适用于本机 unsigned 验证与 CI signed tag；build-info 明确标注签名状态。
tasks.register("archiveRelease") {
    group = "distribution"
    description = "Assemble release and collect APK, mapping and build metadata."
    dependsOn("assembleRelease")
    inputs.property("releaseSigningReady", releaseSigningReady)
    outputs.dir(releaseArchiveDir)

    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val signedApk = File(apkDir, "app-release.apk")
        val unsignedApk = File(apkDir, "app-release-unsigned.apk")
        val requireSigned = envOrProperty("RELEASE_SIGNING_REQUIRED") == "true"
        val apkFile = when {
            signedApk.isFile -> signedApk
            unsignedApk.isFile -> unsignedApk
            else -> throw GradleException("release APK 不存在：$apkDir")
        }
        val signed = apkFile == signedApk
        if (requireSigned && !signed) {
            throw GradleException("RELEASE_SIGNING_REQUIRED=true，但产物是 unsigned APK")
        }

        val archiveDir = releaseArchiveDir.get().asFile
        if (!archiveDir.isDirectory && !archiveDir.mkdirs()) {
            throw GradleException("无法创建归档目录：$archiveDir")
        }
        val archivedApkName = if (signed) {
            "adv-rust-$releaseVersionName-release.apk"
        } else {
            "adv-rust-$releaseVersionName-release-unsigned.apk"
        }
        copy {
            from(apkFile)
            into(archiveDir)
            rename { archivedApkName }
        }
        val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
        if (mapping.isFile) {
            copy {
                from(mapping)
                into(archiveDir)
            }
        }
        File(archiveDir, "build-info.json").writeText(
            """
            {
              "applicationId": "$releaseApplicationId",
              "versionName": "$releaseVersionName",
              "versionCode": $releaseVersionCode,
              "buildType": "release",
              "minifyEnabled": false,
              "signed": $signed,
              "apkFileName": "$archivedApkName",
              "apkSizeBytes": ${apkFile.length()},
              "generatedAtUtc": "${Instant.now()}"
            }
            """.trimIndent() + "\n",
        )
        println("RELEASE_ARCHIVE_OK dir=$archiveDir apk=$archivedApkName signed=$signed")
    }
}
