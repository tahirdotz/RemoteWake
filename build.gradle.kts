// Plugin versions are declared once here and applied in :app.
// AGP 9 compiles Kotlin itself; the Kotlin plugin is only listed to pin the Kotlin version.
plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
}
