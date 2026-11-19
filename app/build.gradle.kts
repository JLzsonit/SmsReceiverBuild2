plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.smsreceiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.smsreceiver"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.7"
    }

    // 固定签名：仓库内自带 keystore，保证每次云端构建签名一致，
    // 用户更新 App 时可直接覆盖安装，不再报"签名冲突"。
    signingConfigs {
        create("release") {
            storeFile = file("keystore/debug.p12")
            storePassword = "android123"
            keyAlias = "androiddebugkey"
            keyPassword = "android123"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            // debug 构建也用固定签名，云端产出的 app-debug.apk 签名不再随机
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // 底部导航图标 + 界面图标
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // ntfy 订阅长连接（HTTP 流式读取）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON 解析与本地持久化
    implementation("com.google.code.gson:gson:2.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
