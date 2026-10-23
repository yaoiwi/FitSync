plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fitsync"
    compileSdk = 37

    defaultConfig {
        // 代码包名是 com.fitsync（决定 R / BuildConfig 的位置），
        // applicationId 多一级 .app，避免以后上架时与别人的 com.fitsync 冲突。
        applicationId = "com.fitsync.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 45
        versionName = "0.10.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-ui"))
    // 详情页直接查采样点/轨迹表，需要 Room 的类型（core-data 是 implementation 引入的）
    implementation(libs.room.runtime)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // Material2 只用于下拉刷新（material3 1.2.x 的 pull-to-refresh 仍是实验 API 且不完整）
    implementation(libs.compose.material)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Activity & Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 注意：不要加 lifecycle-runtime-compose 2.8.x —— 它的 LocalLifecycleOwner 与 Compose 1.6.x 不兼容，
    // 用 collectAsStateWithLifecycle 会在首帧抛 "CompositionLocal LocalLifecycleOwner not present"。
    // 等 Compose 升到 1.7+ 再启用。
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    // Hilt 2.60.1 的注解处理器走非 shaded 的 kotlin-metadata-jvm 2.3.21，
    // 读不了 Kotlin 2.4.10 产物（元数据 2.4.0）。在 kapt 类路径上顶到编译器同版本。
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // OkHttp：登录层要用 MemoryCookieJar（其父类型是 OkHttp 的 CookieJar）
    implementation(libs.okhttp)

    // WorkManager（定时同步）
    implementation(libs.work.runtime.ktx)

    // 单测（JVM 级冒烟测试：Room + Compose 渲染）
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.core)
    // core-data 对 Room 用的是 implementation，单测要直接建库，这里显式补上
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)
    testImplementation(libs.sqlite.framework)
    testImplementation(libs.okhttp)
    debugImplementation(libs.compose.ui.test.manifest)
}