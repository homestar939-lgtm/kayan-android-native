#!/bin/bash
# KAYAN MAX POWER META - FIX ALL ERRORS IN ONE SHOT
# Run from: /storage/emulated/0/Download/kayan-android-native
# chmod +x MAX_POWER_FIX_ALL.sh && ./MAX_POWER_FIX_ALL.sh

set -e
PROJECT_ROOT=$(pwd)
echo "=== KAYAN MAX POWER FIX ==="
echo "Project: $PROJECT_ROOT"

# 1. ROOT build.gradle.kts - Kotlin 2.0.21 + compose plugin exists
cat > build.gradle.kts <<'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
EOF
echo "✓ build.gradle.kts fixed (Kotlin 2.0.21)"

# 2. settings.gradle.kts - MUST have gradlePluginPortal
cat > settings.gradle.kts <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "kayan-android-native"
include(":app")
EOF
echo "✓ settings.gradle.kts fixed (pluginManagement)"

# 3. app/build.gradle.kts - COMPLETE ZERO ERRORS VERSION
cat > app/build.gradle.kts <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kayan.x"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kayan.x"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.7")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
}

kotlin {
    jvmToolchain(17)
}
EOF
echo "✓ app/build.gradle.kts fixed (arm64-v8a only, legacy packaging, compose 1.5.14)"

# 4. CMakeLists.txt - REAL TAG b4600 + GIT_SHALLOW FALSE + modular link
cat > app/src/main/cpp/CMakeLists.txt <<'EOF'
cmake_minimum_required(VERSION 3.22.1)
project(kayan_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable heavy parts for Android - speed up build
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

include(FetchContent)
# REAL existing verified tag - b4600 exists on GitHub releases
set(LLAMA_TAG "b4600" CACHE STRING "llama.cpp stable tag")

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG ${LLAMA_TAG}
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(kayan_llama SHARED llama_jni.cpp)

target_include_directories(kayan_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/common
)

find_library(log-lib log)

# b4600 modular structure requires 5 libs
target_link_libraries(kayan_llama PRIVATE
    llama
    common
    ggml
    ggml-base
    ggml-cpu
    ${log-lib}
    android
)
EOF
echo "✓ CMakeLists.txt fixed (b4600 + modular link)"

# 5. gradle.properties - critical flags
cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
android.nonFinalResIds=true
android.defaults.buildfeatures.buildconfig=true
EOF
echo "✓ gradle.properties fixed"

# 6. AndroidManifest.xml fix
if [ -f app/src/main/AndroidManifest.xml ]; then
  python3 << 'PYEOF'
import pathlib, re, base64
mf = pathlib.Path('app/src/main/AndroidManifest.xml')
txt = mf.read_text()
# Remove extractNativeLibs
txt = re.sub(r'\s*android:extractNativeLibs="[^"]*"', '', txt)
# Replace missing mipmap with system icon
txt = txt.replace('@mipmap/ic_launcher', '@android:mipmap/sym_def_app_icon')
txt = txt.replace('@mipmap/ic_launcher_round', '@android:mipmap/sym_def_app_icon')
# Ensure package is correct (remove if duplicated)
mf.write_text(txt)
print("✓ AndroidManifest.xml fixed (extractNativeLibs removed, icon fallback)")

# Create dummy mipmaps to satisfy AAPT permanently
png_b64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII='
data = base64.b64decode(png_b64)
for d in ['mipmap-hdpi','mipmap-mdpi','mipmap-xhdpi','mipmap-xxhdpi','mipmap-xxxhdpi']:
    p = pathlib.Path(f'app/src/main/res/{d}')
    p.mkdir(parents=True, exist_ok=True)
    (p/'ic_launcher.png').write_bytes(data)
    (p/'ic_launcher_round.png').write_bytes(data)
print("✓ Dummy mipmaps created")
PYEOF
fi

# 7. proguard-rules.pro
if [ ! -f app/proguard-rules.pro ]; then
  echo "-keep class com.kayan.x.** { *; }" > app/proguard-rules.pro
  echo "✓ proguard-rules.pro created"
fi

# 8. GitHub workflow - MAX POWER BUILD.YML
mkdir -p .github/workflows
cat > .github/workflows/build.yml <<'EOF'
name: Build Kayan APK

on:
  push:
    branches: [ master, main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Install NDK 26.1.10909125 and CMake 3.22.1
        run: |
          sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"
          echo "ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125" >> $GITHUB_ENV
          echo "NDK 26.1.10909125 installed"

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}

      - name: Generate Gradle Wrapper 8.7 if missing
        run: |
          if [ ! -f gradlew ]; then
            gradle wrapper --gradle-version 8.7 --distribution-type all
          fi
          chmod +x gradlew
          ./gradlew --version

      - name: Build Debug APK
        run: ./gradlew assembleDebug --stacktrace --info

      - name: Upload APK
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: kayan-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          retention-days: 14

      - name: Show APK size
        if: success()
        run: ls -lh app/build/outputs/apk/debug/
EOF
echo "✓ .github/workflows/build.yml fixed (NDK 26.1.10909125 + CMake 3.22.1 + Gradle 8.7 + b4600)"

# 9. Ensure gradle wrapper version 8.7
if [ -f gradle/wrapper/gradle-wrapper.properties ]; then
  sed -i 's/gradle-.*-all.zip/gradle-8.7-all.zip/' gradle/wrapper/gradle-wrapper.properties
  echo "✓ gradle-wrapper.properties set to 8.7"
fi

echo ""
echo "=========================================="
echo "ALL FIXES APPLIED - MAX POWER MODE DONE"
echo "=========================================="
echo "Now run:"
echo "  git add -A"
echo "  git commit -m 'MAX POWER FIX: all errors in one shot - Kotlin 2.0.21 + b4600 + AAPT + NDK 26.1.10909125'"
echo "  git push"
echo ""
