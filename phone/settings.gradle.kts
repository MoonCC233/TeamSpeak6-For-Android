pluginManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven {
                url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven {
                url = uri("https://maven.aliyun.com/repository/google")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }

        google()
        mavenCentral()
    }
}

rootProject.name = "TS6_Droid"
// :core — 手机端与 Pad 端共用的 TS6/语音/屏幕共享核心（无 UI 页面依赖）
include(":core")
include(":app")
// :pad — 原生 Compose 平板客户端（com.yuaxi.ts6pad / dev.tsdroid.pad，AGPL-3.0-only）
include(":pad")
