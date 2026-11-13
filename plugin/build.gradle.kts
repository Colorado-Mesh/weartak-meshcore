plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.coloradomesh.weartak"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.coloradomesh.weartak"
        // Wear OS 4, as for WearMesh. WearTAK itself needs API 34.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-dev"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Release signing is not set up: published builds are expected to be signed by the
            // TAK Product Center pipeline (tak.gov), not with a Colorado Mesh key.
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
