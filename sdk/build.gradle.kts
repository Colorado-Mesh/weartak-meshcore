// The WearTAK plugin SDK, compiled from source as part of this build.
//
// WearTAK does not publish a signed SDK AAR. Its host build compiles a pinned commit of the SDK
// plus an additive "transport-v1" overlay (weartak-wearos ci/plugin-sdk.json). This module does
// the same: the pinned SDK is the weartak-plugin-sdk submodule, and the overlay's sources are
// copied into transport-v1/. See sdk/README.md for the pins and how to move to newer ones.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.weartak.plugin.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 30
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("../weartak-plugin-sdk/src/main/java", "transport-v1/main/java")
            aidl.srcDirs("../weartak-plugin-sdk/src/main/aidl", "transport-v1/main/aidl")
        }
    }

    buildFeatures {
        aidl = true
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
    implementation(libs.androidx.core.ktx)
}
