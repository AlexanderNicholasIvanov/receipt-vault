// Runs the minified app (the "benchmark" build type: release + debug key) on an emulator and
// clicks through it. R8 problems only show up at runtime, so this guards the release build. It also
// records the baseline profile and measures cold start (BaselineProfileGenerator, StartupBenchmark).
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
        // CI runs on an emulator: startup numbers are only roughly right there, but the baseline profile
        // it records is the same as on a phone.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "RECORDS_PROFILE", "false")
        }
        // Against the app's un-minified "profile" build: only BaselineProfileGenerator runs here.
        create("profile") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("benchmark", "release")
            buildConfigField("boolean", "RECORDS_PROFILE", "true")
        }
    }

    buildFeatures {
        buildConfig = true
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
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" || it.buildType == "profile" }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
