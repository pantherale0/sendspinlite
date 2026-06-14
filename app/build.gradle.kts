plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.sendspinlite"
    compileSdk = 36

    // NDK r27 with explicit 16 KB linker flags in CMake (see app/src/main/cpp/CMakeLists.txt).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.sendspinlite"
        minSdk = 24
        targetSdk = 36
        versionCode = 17
        versionName = "1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Sentry DSN — set via environment variable SENTRY_DSN or leave empty to disable crash reporting
        buildConfigField("String", "SENTRY_DSN", "\"${System.getenv("SENTRY_DSN") ?: ""}\"")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            // AAudio (used transitively by sendspin-cpp host build deps) requires API 26+,
            // but the bridge itself only needs POSIX sockets; ship the common ABIs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val hasSigningEnv =
                listOf(
                    "KEYSTORE_FILE",
                    "KEYSTORE_PASSWORD",
                    "KEY_ALIAS",
                    "KEY_PASSWORD",
                ).all { !System.getenv(it).isNullOrBlank() }
            if (hasSigningEnv) {
                signingConfig = signingConfigs.getByName("release")
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
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Core 1.9.0+ has ashmem pinning fixes and supports API 24+
    implementation("androidx.core:core-ktx:1.9.0")

    // Activity Compose 1.7.0+ supports API 24+ and works with compileSdk 31
    implementation("androidx.activity:activity-compose:1.7.0")

    // Compose 1.0.x is the most stable for API 24+
    implementation("androidx.compose.ui:ui:1.0.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.0.5")
    implementation("androidx.compose.material:material:1.0.5")
    implementation("androidx.compose.material:material-icons-extended:1.0.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.0.5")

    // Coroutines 1.6.x for API 24+ compatibility
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    // JSON library supports API 24+
    implementation("org.json:json:20231013")

    // Sentry Android SDK — opt-in crash and ANR reporting
    implementation("io.sentry:sentry-android:8.14.0")

    // Currently supporting: PCM

    // androidx.media 1.6.0+ has ashmem pinning fixes and supports API 14+
    implementation("androidx.media:media:1.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.0.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.0.5")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
}
