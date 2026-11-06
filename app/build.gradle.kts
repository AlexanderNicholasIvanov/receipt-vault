plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // NOTE: no org.jetbrains.kotlin.android -- AGP 9 has built-in Kotlin.
}

// CI passes -Preceiptvault.versionCode=<run number>; local builds are version 1.
val appVersionCode: Int =
    (project.findProperty("receiptvault.versionCode") as String?)?.trim()?.toIntOrNull() ?: 1

// GitHub repo whose Releases the in-app update check polls.
val updateRepo: String =
    (project.findProperty("receiptvault.updateRepo") as String?)?.takeIf { it.isNotBlank() }
        ?: "AlexanderNicholasIvanov/receipt-vault"

// Release signing comes from env vars / gradle properties (CI secrets). When no
// keystore is configured the release build falls back to the debug key so a
// local assembleRelease still works.
fun signingValue(name: String): String? =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
val releaseKeystore: File? =
    signingValue("RV_KEYSTORE_PATH")?.let { File(it) }?.takeIf { it.exists() }

android {
    namespace = "com.alexivanov.receiptvault"
    // Compose BOM 2026.08 requires compiling against API 37. targetSdk 36 is
    // Android 16, the platform One UI 8 ships on.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.alexivanov.receiptvault"
        // Built for One UI 8 (Android 16) but installs on Android 13+ so older
        // Galaxy phones are not locked out. API 33 gives us the async Geocoder.
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"

        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = signingValue("RV_KEYSTORE_PASSWORD")
                keyAlias = signingValue("RV_KEY_ALIAS")
                keyPassword = signingValue("RV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Unminified on purpose: readable stack traces from sideloaded builds.
            isMinifyEnabled = false
            // Every One UI 8 phone is 64-bit ARM. Dropping the other ABIs' copies of the
            // ML Kit OCR native library roughly quarters the download.
            ndk { abiFilters += "arm64-v8a" }
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("ReceiptVault: RV_KEYSTORE_PATH unset; release APK will be DEBUG-SIGNED.")
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // New library releases appear constantly; that is not a build failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // targetSdk is pinned to Android 16 (One UI 8) on purpose; raising it is a tested, deliberate change.
        disable += "OldTargetApi"
        // Phone app; release builds ship arm64 only (see abiFilters).
        disable += "ChromeOsAbiSupport"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.exifinterface)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.document.scanner)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
