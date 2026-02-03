plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sendspinlite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sendspinlite"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "1.0"
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
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
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

    // OkHttp 4.11.0 supports API 21+
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // JSON library supports API 24+
    implementation("org.json:json:20231013")

    // Concentus library
    implementation("io.github.jaredmdobson:concentus:1.0.2")

    // FLAC support can be added later with proper library selection
    // Currently supporting: Opus (via Concentus) and PCM

    // androidx.media 1.6.0+ has ashmem pinning fixes and supports API 14+
    implementation("androidx.media:media:1.6.0")
}
