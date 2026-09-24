plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sideroca.voicetuner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sideroca.voicetuner"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.4.1"
    }

    signingConfigs {
        getByName("debug") {
            // 固定签名密钥：任何环境构建的 APK 均可互相覆盖安装
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        disable.addAll(
            setOf(
                "HardcodedText", "SetTextI18n",
                "IconLauncherShape", "MonochromeLauncherIcon",
                "OldTargetApi", "ChromeOsAbiSupport",
                "ButtonStyle", "Autofill", "Overdraw",
                "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable"
            )
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
