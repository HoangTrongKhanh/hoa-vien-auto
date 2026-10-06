import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.hoavien.auto"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.hoavien.auto"
        minSdk = 30             // AccessibilityService.takeScreenshot() cần Android 11
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-thu"
    }

    // Ảnh mẫu dùng chung với bản Python: đóng gói thẳng thư mục templates/ ở gốc dự án.
    sourceSets["main"].assets.srcDir("../../templates")

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }     // máy thật + máy ảo
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }               // điện thoại/máy tính bảng thật
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // bản thử: ký bằng khoá debug để cài thẳng
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":vision"))
    implementation("org.opencv:opencv:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
