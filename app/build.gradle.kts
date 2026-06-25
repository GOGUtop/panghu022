import java.io.File
import java.net.URL
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.xixijiuguan.mysillytavernxixi"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.xixijiuguan.mysillytavernxixi"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 📱 内置 Node.js：优先只打手机常用架构，避免 APK 过大。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 🌐 ML Kit 模型文件不要被压缩，否则加载会失败
    androidResources {
        noCompress += listOf("tflite")
    }

    // 📦 把 Node.js Mobile 的 libnode.so 打进 APK。
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libnode/bin")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ⚠️ 防止 ML Kit 内部依赖与其他库的 META-INF 冲突
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
        jniLibs {
            pickFirsts += listOf("**/libnode.so", "**/libc++_shared.so")
        }
    }
}

// ================================================================
// 🦴 狗骨酒馆：构建时自动下载 Node.js Mobile 官方 Android 二进制
// 如果你的网络访问 GitHub 慢，可以手动下载同名 zip 后放到 app/build/nodejs-mobile/。
// ================================================================
val nodeMobileVersion = "0.3.3"
val nodeMobileFileName = "nodejs-mobile-v${nodeMobileVersion}-android.zip"
val nodeMobileUrl = "https://github.com/JaneaSystems/nodejs-mobile/releases/download/nodejs-mobile-v${nodeMobileVersion}/${nodeMobileFileName}"
val nodeMobileZip = layout.buildDirectory.file("nodejs-mobile/${nodeMobileFileName}")
val nodeMobileOutDir = layout.projectDirectory.dir("libnode")

tasks.register("downloadNodeMobile") {
    outputs.file(nodeMobileZip)
    doLast {
        val outFile = nodeMobileZip.get().asFile
        if (outFile.exists() && outFile.length() > 1024 * 1024) return@doLast
        outFile.parentFile.mkdirs()
        println("Downloading Node.js Mobile: ${nodeMobileUrl}")
        URL(nodeMobileUrl).openStream().use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

tasks.register("extractNodeMobile") {
    dependsOn("downloadNodeMobile")
    outputs.dir(nodeMobileOutDir)
    doLast {
        val outDir = nodeMobileOutDir.asFile
        val check = file("${outDir.absolutePath}/bin/arm64-v8a/libnode.so")
        if (check.exists() && check.length() > 1024 * 1024) return@doLast
        outDir.mkdirs()
        ZipFile(nodeMobileZip.get().asFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name.removePrefix("nodejs-mobile-v${nodeMobileVersion}-android/")
                if (!(name.startsWith("bin/") || name.startsWith("include/"))) continue
                val dest = File(outDir, name)
                dest.parentFile.mkdirs()
                zip.getInputStream(e).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }
}

tasks.matching { it.name == "preBuild" || it.name.startsWith("externalNativeBuild") }.configureEach {
    dependsOn("extractNodeMobile")
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // 新增：腾讯 X5 内核依赖
    implementation("com.tencent.tbs:tbssdk:44286")

    // 🌐 Google ML Kit 离线翻译（约 5MB 库 + 首次联网下载约 30MB 中英模型，之后全离线）
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}