import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.tsdroid.han"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yuaxi.ts6droid.cn"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "2.1.6-Han"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a is the only ABI whose libtslib_jni.so is current: the x86_64 copy
            // predates the file-manager/permission API, and armeabi-v7a / x86 have no core
            // library at all. Restricting to one ABI keeps every installed device working
            // instead of letting x86_64 silently fail or 32-bit devices die on launch,
            // because TsDroidApp loads libtslib_jni during class initialisation.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Signing credentials come from environment variables (CI) or from a
        // gitignored keystore.properties next to release.keystore (local
        // builds). No password is ever stored in this repository. Without
        // either source the build falls back to the debug key, so plain
        // `assembleDebug` keeps working out of the box.
        val keystoreFile = file(System.getenv("SIGNING_KEYSTORE_FILE") ?: "${rootDir}/release.keystore")
        val localProps = Properties().apply {
            val f = file("${rootDir}/keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun credential(envName: String, propName: String): String? =
            System.getenv(envName) ?: localProps.getProperty(propName)

        val storePass = credential("SIGNING_STORE_PASSWORD", "storePassword")
        val keyAliasName = credential("SIGNING_KEY_ALIAS", "keyAlias")
        val keyPass = credential("SIGNING_KEY_PASSWORD", "keyPassword")
        if (keystoreFile.exists() && storePass != null && keyAliasName != null && keyPass != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // Shrinking is what keeps the APK near ~15 MB instead of ~70 MB:
            // without it every unused class is packaged, including the ~11k
            // Material icon classes (see proguard-rules.pro for the JNI keep
            // rule). Crash reports then need mapping.txt to be readable, which
            // the release workflow archives.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No release credentials on this machine: fall back to the debug
                // key so `assembleRelease` still emits an installable APK for
                // local size/behaviour testing. The release workflow always
                // injects the real keystore, so published builds are unaffected.
                logger.warn(
                    "release: SIGNING_* credentials are not set — signing with the " +
                        "DEBUG key. Do not distribute this APK."
                )
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }
}

// Rust 原生库（libtslib_jni.so）与 bridge/data/service/screen 等共享代码已迁到 :core，
// 需要重建 .so 时在 phone/ 下执行 `gradlew :core:buildRustLibs`（详见 core/build.gradle.kts）。

dependencies {
    // TS6 协议、语音、屏幕共享与共享 ViewModel 全部来自 :core
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.mikepenz.markdown.m3)
    // 屏幕共享（A3/A4）：WebRTC 媒体层（发布端屏幕采集 / 观看端渲染）
    implementation(libs.webrtc.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

tasks.withType<Test>().configureEach {
    // Non-ASCII project paths on Windows break class loading unless the
    // test worker reads paths as UTF-8
    jvmArgs("-Dfile.encoding=UTF-8")
}
