plugins {
    id("com.android.application")
}

android {
    namespace = "com.wbhub.testapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wbhub.testapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // 样本 .so 用 NDK 单独编译后放在 jniLibs 里
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
