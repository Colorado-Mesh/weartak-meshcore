plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.coloradomesh.weartak"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.coloradomesh.weartak"
        // Wear OS 4, as for WearMesh. WearTAK itself needs API 34.
        minSdk = 33
        targetSdk = 35
        versionCode = 3
        versionName = "0.0.3-dev"
    }

    signingConfigs {
        // One debug key for every build, local and CI. WearTAK pins a plugin's signing
        // certificate the first time it sees it and then refuses any other, so debug builds
        // signed with each machine's own debug key could not replace one another. This key is
        // for debug builds only and is public on purpose; it uses Android's standard debug
        // passwords.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            // Release signing is not set up: published builds are expected to be signed by the
            // TAK Product Center pipeline (tak.gov), not with a Colorado Mesh key.
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":sdk"))
    // The Bluetooth link and session to the radio; brings in org.coloradomesh.meshcore:protocol.
    implementation("org.coloradomesh.meshcore:ble")
    // Mesh to CoT (MeshPicture, CotEvents), shared with the bridge.
    implementation("org.coloradomesh.meshcore:tak")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    testImplementation(libs.junit)
}
