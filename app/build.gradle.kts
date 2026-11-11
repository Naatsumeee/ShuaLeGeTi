import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.examhelper.shua"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.examhelper.shua"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.1.0"
    }

    // 正式签名：从仓库根目录的 keystore.properties 读取（该文件与 release.jks 都在 .gitignore 里）。
    //
    // 为什么用外部文件而不是硬编码：signingConfigs 会把密码写进构建脚本，一旦提交就永久留在 git 历史里。
    //
    // 没配时优雅降级到 debug 签名，保证克隆下来 `./gradlew assembleRelease` 仍能出包
    // （只是那个包不能覆盖已装的正版）。要出正式包，在根目录放：
    //   storeFile=release.jks
    //   storePassword=...
    //   keyAlias=shualegeti
    //   keyPassword=...
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasReleaseKey = keystoreProps.getProperty("storePassword").isNullOrBlank().not()

    // ABI 分包：控制单包体积（模型 + 双 OCR 原生库较大）
    //
    // v1.0.12 起只发 arm64-v8a：
    //  · armeabi-v7a 是 2017 年前的 32 位老机与少数低价机，本项目目标用户是大学生，
    //    实际装机量接近 0，却让 Release 多 70MB；
    //  · x86_64 只有模拟器/Chromebook 用，真实手机为 0。
    // 需要给模拟器或老机出包时用命令行覆盖，不必改代码：
    //    ./gradlew assembleDebug -PabiOverride=arm64-v8a,x86_64
    val abiOverride = (project.findProperty("abiOverride") as String?)
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    splits {
        abi {
            isEnable = true
            reset()
            include(*(abiOverride ?: listOf("arm64-v8a")).toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有正式 keystore 就用它；没有则退回 debug 签名（包能出，但不能覆盖已装正版）
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/DEPENDENCIES"
    }
}

/**
 * Release APK 统一命名：`Shualegeti-<abi>-<version>.apk`，例如 `Shualegeti-arm64-v8a-1.1.0.apk`。
 *
 * 为什么只改 release、不改 debug：`启动模拟器.bat` 按 `app-<abi>-debug.apk` 找包装机，
 * 改 debug 名会把这个脚本弄坏。分发产物才需要跟着版本号走，调试卷不需要。
 *
 * 说明：这里用的是 `AppExtension.applicationVariants` + `BaseVariantOutputImpl`（内部 API）。
 * AGP 8 的 `androidComponents` 新 API 里的 VariantOutput 是不可变的，没有 setter，
 * 想自定义文件名目前只能走这条老路。`filters` 里取 ABI 用的是 `filterType.toString()`
 * 而不是枚举相等，是为了在 AGP 换了该类型实现时不至于编译失败。
 */
run {
    val androidExt = project.extensions.getByName("android")
        as com.android.build.gradle.AppExtension
    // 必须用 configureEach 而不是 all：DomainObjectSet 同时是 Set，
    // Kotlin 会把 `.all {}` 解析成标准库的 Iterable.all(predicate)（要求返回 Boolean），
    // 编译期就报 "inferred type is Unit but Boolean was expected"。
    androidExt.applicationVariants.configureEach {
        if (buildType.name != "release") return@configureEach
        val vn = versionName
        outputs.configureEach {
            @Suppress("DEPRECATION")
            val impl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = impl.filters
                .firstOrNull { it.filterType.toString().equals("ABI", ignoreCase = true) }
                ?.identifier ?: "universal"
            impl.outputFileName = "Shualegeti-$abi-$vn.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.mlkit.text.chinese)
    implementation(project(":ocrlibrary"))
    implementation(libs.pdfbox.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(kotlin("test"))
}
