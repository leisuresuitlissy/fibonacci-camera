plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.lissy.fibcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lissy.fibcam"
        minSdk = 29
        targetSdk = 35
        versionCode = runNumber
        // The readable version: the line in version.txt (e.g. 0.2) plus the build number, e.g. 0.2.18.
        versionName = rootProject.file("version.txt").takeIf { it.exists() }?.readText()?.trim()?.let { "$it.$runNumber" } ?: "0.1.$runNumber"
    }

    buildFeatures { buildConfig = true }

    // direct: sideloaded beta builds with the in-app updater (the APK behind the QR code).
    // play:   for Google Play, where Play handles updates: no updater, no install permission.
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") { dimension = "distribution"; buildConfigField("boolean", "UPDATER_ENABLED", "true") }
        create("play") { dimension = "distribution"; buildConfigField("boolean", "UPDATER_ENABLED", "false") }
    }

    // One fixed key, committed on purpose: every build is signed the same way, so a new APK
    // installs straight over the old one instead of failing with "app not installed".
    // It's a sideload key, not a Play Store key.
    signingConfigs {
        create("shared") {
            storeFile = file("fibcam.keystore")
            storePassword = "fibcam"
            keyAlias = "fibcam"
            keyPassword = "fibcam"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
