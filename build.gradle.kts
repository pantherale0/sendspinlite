plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // ✅ Kotlin 2.x Compose compiler plugin (matches Kotlin version)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
