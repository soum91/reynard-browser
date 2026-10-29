plugins {
    id("com.android.application")
}

// CI stamps the run number in, so every build installs over the previous one.
val buildNumber = (System.getenv("REYNARD_BUILD_NUMBER") ?: "1").toInt()
val keystorePath: String? = System.getenv("REYNARD_KEYSTORE")
val fxaExperiment = System.getenv("REYNARD_FXA_EXPERIMENT") == "true"

android {
    namespace = "com.soum91.reynardtv"
    // Current GeckoView requires compiling against Android 17 (API 37.2).
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.soum91.reynardtv"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.2.0"
        ndk {
            // Most Google TV sets run a 32-bit userland even on 64-bit chips, so ship both.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        // Stable fallback key (public, committed) so test builds install over each other.
        // Real releases use the private key from the REYNARD_TV_KEYSTORE_* repository secrets.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("REYNARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REYNARD_KEY_ALIAS") ?: "reynard"
                keyPassword = System.getenv("REYNARD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Allow experimental Mozilla account testing alongside stable Reynard TV.
            if (fxaExperiment) {
                applicationIdSuffix = ".fxatest"
                versionNameSuffix = "-fxatest"
            }
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Compressed native libs: a much smaller APK to copy to the TV.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Latest GeckoView release from Mozilla's Maven; CI prints the resolved version.
    implementation("org.mozilla.geckoview:geckoview:+")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
