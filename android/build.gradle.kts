plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin). Declaring KGP here only picks the
    // compiler version, which must be at least the kotlin-stdlib GeckoView depends on.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
