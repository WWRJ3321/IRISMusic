import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名凭据：从项目根的 keystore.properties 读取（该文件与 .jks 都不入库）。
// 缺失时回落 debug 签名，保证别人 clone 下来仍能编译出可安装的包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { stream -> load(stream) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")
val hasReleaseKeystore = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.iris.music"
    compileSdk = 34

    // 按 ABI 拆分：每个架构单独出 APK，只装 arm64-v8a 那份，安装占用更小
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.iris.music"
        minSdk = 24
        targetSdk = 34
        versionCode = 3700
        versionName = "3.7.0Beta"

        // 只保留中英文资源，去掉其它语言的 Compose/AndroidX 字符串，减小体积
        resourceConfigurations += listOf("en", "zh")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // 提供 BuildConfig.VERSION_NAME，供「关于」页读取，避免版本号硬编码脱节
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended removed: 5MB+ of icon classes we don't use

    // 背景模糊（毛玻璃/backdrop blur），~100KB
    implementation("dev.chrisbanes.haze:haze:0.7.3")

    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")

    // Coil + okhttp removed: ~8MB installed size saved.
    // Album art loaded via ContentResolver + ThumbnailUtils instead.
    // palette-ktx removed: not used anywhere.
}