import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.tsdroid.pad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yuaxi.ts6pad"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-native"

        // Same reason as :app — libtslib_jni.so exists for arm64-v8a only.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Optional local/CI release signing. Without credentials the release
        // build falls back to the debug key, exactly like :app.
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "pad release: SIGNING_* credentials are not set — signing with the " +
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
        // 加入页显示的应用版本号来自 BuildConfig.VERSION_NAME，随构建版本自动更新
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
        }
    }
}

dependencies {
    // TS6 protocol, identity, voice, chat state and the connection service.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8")
}
