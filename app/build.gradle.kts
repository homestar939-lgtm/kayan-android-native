import com.android.build.api.dsl.AbiSplit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace   = "com.kayan.x"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "com.kayan.x"
        minSdk          = 26          // Android 8: required for DocumentFile SAF improvements
        targetSdk       = 35
        versionCode     = 1
        versionName     = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── NDK / CMake ─────────────────────────────────────────────────────────
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-DNDEBUG")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_USE_VULKAN=OFF",      // override via local.properties if Vulkan available
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DGGML_NATIVE=OFF"           // must be OFF for cross-compile
                )
            }
        }

        // Only package these ABIs — each produces its own APK via splits below
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // ── External Native Build ────────────────────────────────────────────────
    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ── ABI Splits ──────────────────────────────────────────────────────────
    // Produces separate, smaller APKs per architecture.
    // arm64-v8a alone saves ~10-15 MB vs a fat APK on modern devices.
    splits {
        abi {
            isEnable         = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk   = false   // no fat APK in release; set true for debug convenience
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // ABI-split version codes so Play Store can distinguish builds
            splits.abi.applicationVersionCode?.let { versionCodeOverride ->
                // injected automatically by Android Gradle Plugin
            }
        }
        debug {
            isDebuggable    = true
            isMinifyEnabled = false
            // During debug, build universal APK for convenience
            splits.abi.isUniversalApk = true
        }
    }

    buildFeatures {
        compose      = true
        buildConfig  = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Do NOT strip debug symbols in debug builds (helpful for NDK crash analysis)
        jniLibs {
            useLegacyPackaging = false  // use uncompressed .so for faster install
        }
    }

    // ── Lint ────────────────────────────────────────────────────────────────
    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
}

// ── Version code per ABI (Play Store requirement for ABI splits) ─────────────
val abiCodes = mapOf("arm64-v8a" to 1, "x86_64" to 2)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            val abiVersionCode = abiCodes[abi] ?: 0
            output.versionCode.set((output.versionCode.get() ?: 1) + abiVersionCode * 1000)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.timber)

    debugImplementation(libs.androidx.ui.tooling)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
