plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.fitgarminsync.data"
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
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    // Room 2.8.5 处理器内置 kotlin-metadata-jvm 2.2（最高读元数据 2.3），
    // Kotlin 2.4.10 编译产物是元数据 2.4，不顶版本会报
    // "Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0"。
    // 在 kapt 类路径上把元数据库抬到编译器同版本即可，处理器公共 API 未变。
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    // Garmin FIT SDK：健康数据搬运时生成 monitoring FIT
    implementation(libs.garmin.fit)
    implementation(libs.security.crypto)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)

    // 网络层单测：MockWebServer 模拟 Garmin SSO，org.json 提供真实实现（android.jar 里的是桩）
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.sqlite.framework)
}