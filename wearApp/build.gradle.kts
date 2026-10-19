// Nexio课程表 - Wear OS 模块
//
// 两件事：
// 1. 表盘数据源：把课表算成表盘能直接用的 Complication 数据（多种槽位尺寸）
// 2. 手表端小界面：看当前/下一节课 + 表盘显示选项 + 手动同步
//
// 不做完整表盘（交给三星/谷歌/第三方表盘去画），也不做独立课程编辑 ——
// 改课表回手机，手表只负责展示。

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.haooz.chedule.wear"
    // Compose for Wear 1.7.x 要求依赖方编译到 API 37；只影响编译期可用 API，
    // 不改变 targetSdk/minSdk 的运行时行为。
    compileSdk = 37

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
        compose = true
    }

    // Compose 由 Kotlin 2.x 的 compose 编译器插件处理，无需 composeOptions
}

dependencies {
    // 表盘 complication 数据源
    implementation("androidx.wear:wear:1.4.0")
    // ⚠ 用 androidx.wear.watchface 这一套，不是 androidx.wear.complications。
    // 后者是旧包装层，枚举里没有 GOAL_PROGRESS / WEIGHTED_ELEMENTS，
    // 而三星表盘的中央大组件正是请求这两种类型 —— 缺了它这个槽位就选不到我们。
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data:1.3.0")

    // Compose for Wear OS：手表端设置界面
    // 版本钉在 1.7.1（对应 Compose 1.10 构建）；项目 BOM 是 compose 1.11，
    // Wear 的 compose 依赖传递进来的 1.10 会被 BOM 抬到 1.11，二进制兼容。
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // 手机 ↔ 手表通道（DataClient 持久化，断连后自动补发）
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    // Task.await()：GMS 的 Task 转协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("androidx.core:core-ktx:1.13.1")
}
