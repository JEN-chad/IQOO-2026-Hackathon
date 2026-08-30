plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.safescreen"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.safescreen"
        minSdk = 31          // Android 12 — required for Modifier.blur; S22 Ultra ships 12+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // S22 Ultra / S25 Ultra are arm64. Drop other ABIs to shrink the APK (~91MB -> ~30MB)
        // and speed up adb install. (Add x86_64 back if you need an emulator.)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    }
    // .pte models are loaded from assets at runtime; don't let the build tool compress them.
    androidResources {
        noCompress += "pte"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Extract .so to nativeLibraryDir as real files so FastRPC can load the HTP skel by path
            // (ADSP_LIBRARY_PATH points at nativeLibraryDir). Mirrors android:extractNativeLibs=true.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    // On-device inference. App degrades to a heuristic detector if the AAR/.pte is unavailable.
    // QNN-enabled ExecuTorch AAR built from source with the Hexagon backend (the Maven AAR has no QNN),
    // so the app can run Marqo on the NPU. See docs/NPU-EXPORT-RUNBOOK.md.
    implementation(files("libs/executorch-qnn.aar"))
    implementation(libs.soloader)
    implementation(libs.fbjni)

    debugImplementation(libs.androidx.ui.tooling)
}
