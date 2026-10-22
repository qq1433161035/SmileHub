import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.wbhub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wbhub.app"
        minSdk = 26
        targetSdk = 28
        versionCode = 3
        versionName = "0.3.0"
        // 工具链目前只打包 arm64 的 aapt2/zipalign
        ndk {
            abiFilters += "arm64-v8a"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing reads keystore.properties, which is git-ignored. A clone
    // without signing material still builds: the signing config is only created
    // and attached when every input is present, because attaching an empty one
    // makes :app:packageRelease fail with "missing required property
    // storeFile" rather than producing an unsigned APK.
    val keystoreProperties = Properties()
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use {
        keystoreProperties.load(it)
    }

    val releaseStore = keystoreProperties.getProperty("storeFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }
    val hasReleaseSigning = releaseStore != null &&
        !keystoreProperties.getProperty("storePassword").isNullOrBlank() &&
        !keystoreProperties.getProperty("keyAlias").isNullOrBlank() &&
        !keystoreProperties.getProperty("keyPassword").isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStore
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Attached only when the keystore was found; otherwise the release
            // artifact stays unsigned instead of failing the build.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 工具库带入的签名文件与多版本类在 APK 内无意义，且会触发校验失败
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
        jniLibs {
            // aapt2/zipalign 以可执行文件形式随 nativeLibs 解压，必须保留原样
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libaapt2_exec.so", "**/libzipalign_exec.so")
        }
    }
}

// 内置 CPython：Agent 的 python 工具直接在进程内执行脚本，无需设备上另装解释器。
// 构建机上的解释器按平台选择；也可以用 WBHUB_PYTHON 覆盖（例如 "py -3.12"）。
val pythonLauncher = System.getenv("WBHUB_PYTHON")?.trim().orEmpty().ifEmpty {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "py -3.12"
    } else {
        "python3"
    }
}
chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython(*pythonLauncher.split(" ").toTypedArray())
        // 跳过构建期字节码编译，缩短构建时间；首次导入时在设备上编译。
        pyc {
            src = false
            stdlib = false
        }
    }
}

// apktool 等重库不再打进 APK，运行时从 /WBHub/libs/engine.zip 加载。
// 这里只保留一个纯接口模块。
kotlin {
    jvmToolchain(21)
}

tasks.configureEach {
    if (name.contains("AarMetadata", ignoreCase = true)) {
        enabled = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // org.json is part of the Android framework, whose copy is a stub at unit-test
    // time, so the tests get the real implementation instead.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")

    // 逆向引擎全部内置：apktool/apksig/smali/BouncyCastle/jadx/Capstone
    implementation(project(":engine"))

    // 设备内的 ADB 客户端：支持 Android 11+ 无线调试的配对码流程（SPAKE2 + TLS）
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        // 库自带 bcprov-jdk15to18，与 engine 的 bcprov-jdk18on 类名冲突
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    // 自签证书需要 bcpkix（engine 的依赖不会传递给 app 的编译期）
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // 配对要用 TLS 1.3 的 exportKeyingMaterial；系统 Conscrypt 在新 Android 上去掉了该方法
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
