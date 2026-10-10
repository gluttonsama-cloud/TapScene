plugins {
    id("com.android.application")
}

android {
    namespace = "com.tapscene.runtime.target"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.tapscene.runtime.target"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-runtime-fixture"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    lint { abortOnError = true }
}

// No release fixture exists, even when the module is explicitly included.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "debug"
    }
}
