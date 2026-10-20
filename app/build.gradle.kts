import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wbhub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wbhub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
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
        }
    }
}

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
}
