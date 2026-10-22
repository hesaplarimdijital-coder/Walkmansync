// :core — saf Kotlin/JVM. android.* ve androidx.* importu YASAK (bkz. docs/ARCHITECTURE.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Flow/StateFlow genel API'de görünür → api.
    // Not: tools/verify-core.sh coroutines 1.6.4 ile derler; 1.6.4'te olmayan API kullanılmaz.
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
