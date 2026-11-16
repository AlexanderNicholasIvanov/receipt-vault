// Runs the minified app (the "benchmark" build type: release + debug key) on an emulator and
// clicks through it. R8 problems only show up at runtime, so this guards the release build.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.alexivanov.receiptvault.smoketest"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    // The test APK drives the app from outside, like a user: it needs no code from it.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    // Only the minified build is worth testing here; the debug build has the unit tests.
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
}
