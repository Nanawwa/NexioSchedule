// Nexio课程表 - Wear OS 模块
//
// 只做数据源：把课表算成表盘能直接用的 Complication 数据。
// 不提供表盘、不做独立 App UI，装到手表上不占桌面图标。

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.haooz.chedule.wear"
    compileSdk = 36

    defaultConfig {
        // Wear 模块必须与手机端同包名，Data Layer 才能按包名找到同一个应用
        applicationId = (project.findProperty("nexioAppId") as String?) ?: "com.haooz.chedule"
        minSdk = 30
        targetSdk = 34
        versionCode = 165
        versionName = "1.6.5-1015"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // 表盘 complication 数据源
    implementation("androidx.wear:wear:1.4.0")
    implementation("androidx.wear:wear-complications-data-source:1.0.0-alpha22")
    implementation("androidx.wear:wear-complications-data:1.0.0-alpha22")

    // 手机 ↔ 手表通道（DataClient 持久化，断连后自动补发）
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    // Task.await()：GMS 的 Task 转协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("androidx.core:core-ktx:1.13.1")
}
