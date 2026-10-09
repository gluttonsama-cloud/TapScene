plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.compose.screenshot")
}

android {
    namespace = "com.tapscene"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.tapscene"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.6.0-offline-dev"
        testInstrumentationRunner = "com.tapscene.media.MediaCompatibilityInstrumentation"
        manifestPlaceholders["appLabel"] = "TapScene"
    }
    buildTypes {
        debug {
            // The earlier CI debug key was ephemeral. A side-by-side preview preserves its data.
            if (providers.gradleProperty("compatibilityPreview").orNull == "true") {
                applicationIdSuffix = ".preview.hevc"
                manifestPlaceholders["appLabel"] = "TapScene 开发版"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
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
}
