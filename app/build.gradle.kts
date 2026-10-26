plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.visualassistant.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.visualassistant.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 14
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 高德 Web 服务 Key：公开仓库中已替换为占位符。
        // 使用时请改为自己的 Key，或从 local.properties 读取：MapSdkKey=<your-key>
        manifestPlaceholders["AMAP_API_KEY"] =
            (project.findProperty("MapSdkKey") as String?) ?: "YOUR_AMAP_API_KEY"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // 公开仓库的 release 产物使用 debug keystore 签名，保证他人 clone 后
        // 无需任何私钥即可构建出「能直接安装」的 APK。
        // 正式发布请自行创建 keystore 并在 local.properties 中配置签名信息。
        create("publicRelease") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 开启代码压缩与资源压缩，显著减小 APK 体积
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("publicRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    val cameraX = "1.4.2"
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("com.google.ar:core:1.56.0")
    implementation("com.amap.api:location:latest.integration")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
