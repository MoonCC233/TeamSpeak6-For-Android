plugins {
    id("com.android.application")
}

android {
    namespace = "dev.tsdroid.pad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yuaxi.ts6pad"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-pad-b3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
