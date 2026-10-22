import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wbhub.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

configurations.configureEach {
    // apktool needs Guava's Android variant; the JRE one references APIs that
    // do not exist on the platform.
    resolutionStrategy.force("com.google.guava:guava:33.3.0-android")
}

dependencies {
    api(project(":engine-api"))

    // APK 解析/回打包
    implementation(files("../app/libs/apktool-lib-2.10.0-wbhub.jar"))
    implementation("org.apktool:brut.j.common:2.10.0")
    implementation("org.apktool:brut.j.util:2.10.0")
    implementation("org.apktool:brut.j.dir:2.10.0")
    implementation("org.ogce:xpp3:1.1.6") {
        // xpp3 drags an ancient junit into the runtime; nothing here uses it.
        exclude(group = "junit")
    }
    implementation("com.google.guava:guava:33.3.0-android")
    implementation("org.apache.commons:commons-lang3:3.17.0")
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.apache.commons:commons-text:1.12.0")

    // 签名
    implementation("com.android.tools.build:apksig:8.9.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // dex 反汇编 / 汇编
    implementation("com.android.tools.smali:smali:3.0.8")
    implementation("com.android.tools.smali:smali-baksmali:3.0.8")

    // dex 反编译为 Java
    implementation("io.github.skylot:jadx-core:1.5.6")
    implementation("io.github.skylot:jadx-dex-input:1.5.6")
    implementation("io.github.skylot:jadx-input-api:1.5.6")
    implementation("io.github.skylot:jadx-zip:1.5.6")
    implementation("org.slf4j:slf4j-nop:2.0.18")

    // so 解析（Capstone 的 JNI 封装在 jniLibs 里，ELF 解析是纯 Kotlin）
    implementation("com.google.code.gson:gson:2.14.0")
}
