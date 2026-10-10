plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.compose.screenshot")
}

// Phone delivery stays small; emulator/universal packages are explicit build targets.
val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val requestedAbi = providers.gradleProperty("tapsceneAbi").orElse("arm64-v8a").get()
val runtimeSmoke = providers.gradleProperty("tapsceneRuntimeSmoke").orNull == "true"
val packagedAbis = when (requestedAbi) {
    "universal" -> supportedAbis
    in supportedAbis -> listOf(requestedAbi)
    else -> throw GradleException("tapsceneAbi must be one supported ABI or universal")
}

android {
    namespace = "com.tapscene"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.tapscene"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "0.26.0-presentation-clock-dev"
        testInstrumentationRunner = if (runtimeSmoke) "com.tapscene.runtime.ClickRuntimeInstrumentation"
            else "com.tapscene.media.MediaCompatibilityInstrumentation"
        manifestPlaceholders["appLabel"] = "TapScene"
        buildConfigField("boolean", "HOSTED_ENABLED", "false")
        ndk { abiFilters += packagedAbis }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_JOB_POOLS=compile_pool=2;link_pool=1",
                    "-DCMAKE_JOB_POOL_COMPILE=compile_pool", "-DCMAKE_JOB_POOL_LINK=link_pool")
            }
        }
    }
    buildTypes {
        debug {
            // The earlier CI debug key was ephemeral. A side-by-side preview preserves its data.
            if (providers.gradleProperty("compatibilityPreview").orNull == "true") {
                applicationIdSuffix = ".preview.hevc"
                manifestPlaceholders["appLabel"] = "TapScene 开发版"
            }
        }
        create("hostedDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            applicationIdSuffix = ".hosted.dev"
            manifestPlaceholders["appLabel"] = "TapScene 本机托管开发"
            buildConfigField("boolean", "HOSTED_ENABLED", "true")
            // This development target is not a signed phone delivery.
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    // Only locked local model bytes and their licenses are included. The app never downloads models.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("ocr/assets"))
    // Preserve the already stripped official runtime byte-for-byte for supply-chain checks.
    packaging { jniLibs.keepDebugSymbols += "**/libonnxruntime.so" }
    // Share only explicit data/AI PNG checks; never import the instrumentation runner or all androidTest checks.
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    if (runtimeSmoke) {
        // A deliberately separate device lane: never run or package the broad media fixture suite.
        sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/androidTest/java/com/tapscene/runtime"))
        // TCG startup ANR sampled ART inflating DEX before the runner could start (run 38064296590).
        // Change ZIP storage only for this opt-in lane; keep phone packaging and all code unchanged.
        packaging.dex.useLegacyPackaging = false
    } else {
        sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxParallelForks = 1
            it.maxHeapSize = "2g"
            // Robolectric's documented Java 17 module access, scoped to the test JVM.
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                // The one static-AI host check holds real file/directory FDs for fsync.
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Review-only host renders. Tooling and sample states stay outside the shipped app.
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling:1.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-transformer:1.9.4")
    implementation("androidx.media3:media3-inspector:1.9.4")
    implementation("androidx.media3:media3-effect:1.9.4")
    implementation("androidx.media3:media3-common:1.9.4")
    implementation("androidx.media3:media3-container:1.9.4")
}
