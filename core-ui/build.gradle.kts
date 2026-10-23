plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fitsync.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-data"))

    implementation(libs.androidx.core.ktx)
    // 主题模式用 Flow 推送变化
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)

    // Compose
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    // 底部导航 / 列表项的矢量图标
    api("androidx.compose.material:material-icons-core")
    // 官方 Miuix / HyperOS UI 库（与 HyperIsland 3.2.1 同版本 0.9.4）：
    // miuix-ui 组件、miuix-icons 图标、miuix-blur 液态玻璃（RuntimeShader 真实背景模糊）
    api(libs.miuix.ui)
    api(libs.miuix.icons)
    api(libs.miuix.blur)
    // miuix-ui 传递依赖的 material-color-utilities 5.0.1 强制要求 compileSdk 37 以上。
    // 它只用于 Monet 动态取色，我们走固定色板，压到 4.1.1 可以少升一档 compileSdk。
    api("com.materialkolor:material-color-utilities-android:4.1.1") {
        version { strictly("4.1.1") }
    }
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}