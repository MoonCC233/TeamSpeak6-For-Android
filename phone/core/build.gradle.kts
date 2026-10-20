import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.tsdroid.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 29

        // JNI keep rules travel with the classes, so both consumers (phone :app and the Pad
        // module) keep libtslib_jni usable under R8 without repeating the rule.
        consumerProguardFiles("consumer-rules.pro")
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
        getByName("test") {
            java.srcDirs("src/test/java", "src/test/kotlin")
        }
    }
}

// Task to build Rust native libraries via cargo-ndk
// 前置：NDK 27.2.12479018、cargo-ndk、Ninja（Android SDK 的 cmake/3.22.1/bin 自带）。
// 产物直接覆盖 src/main/jniLibs/arm64-v8a/libtslib_jni.so —— arm64-v8a 是唯一受支持 ABI
// （见 phone/app 的 defaultConfig.ndk.abiFilters 说明）。
// 注意：Windows 上必须指定 CMAKE_GENERATOR=Ninja（默认 VS 生成器不支持 Android 交叉编译）；
// 本仓库的 tslib 无需 vendored-openssl（依赖树为纯 Rust 加密栈）。
tasks.register<Exec>("buildRustLibs") {
    val sdkDir = Properties().apply {
        val f = file("${rootDir}/local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: "${System.getProperty("user.home")}/Android/Sdk"
    val ndkDir = System.getenv("ANDROID_NDK_HOME")
        ?: "${sdkDir}/ndk/27.2.12479018".replace('\\', '/')

    workingDir = file("${rootDir}/../tslib")
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("ANDROID_NDK", ndkDir)
    environment("CMAKE_GENERATOR", "Ninja")
    environment("CMAKE_MAKE_PROGRAM", "${sdkDir}/cmake/3.22.1/bin/ninja.exe".replace('\\', '/'))
    environment("CMAKE_POLICY_VERSION_MINIMUM", "3.5")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", "${projectDir}/src/main/jniLibs",
        "build", "--release", "-p", "tslib-jni",
        "-j", "10"
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    // 屏幕共享（A3/A4）：WebRTC 媒体层。api 而非 implementation — 手机端 UI 与 Pad UI
    // 都直接引用 org.webrtc.*（SurfaceViewRenderer 等），需要经 :core 传递到消费端编译期。
    api(libs.webrtc.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

tasks.withType<Test>().configureEach {
    // Non-ASCII project paths on Windows break class loading unless the
    // test worker reads paths as UTF-8
    jvmArgs("-Dfile.encoding=UTF-8")
}
