plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.crosspoint.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.crosspoint.launcher"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "com.crosspoint.launcher.IntegrationChecks"
        // scripts/release.sh passes versionCode; local builds stay at 1 so any release can update them.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = rootProject.file("VERSION").readText().trim()
    }

    // Release key lives outside the repo; scripts/release.sh exports these from the macOS Keychain.
    val releaseKeystore = System.getenv("CROSSPOINT_KEYSTORE")?.let(::file)
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("CROSSPOINT_KEYSTORE_PASSWORD")
            keyAlias = "crosspoint"
            keyPassword = System.getenv("CROSSPOINT_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jmdns)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.ui.tooling)
}
