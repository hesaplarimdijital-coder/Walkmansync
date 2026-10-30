plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 Kotlin'i kendisi derler (yerleşik Kotlin) ve Kotlin 2.2.10 getirir. Kotlin Gradle eklentisini burada
    // uygulamadan bildirmek katalogdaki Kotlin'i derleme sınıf yoluna koyar: AGP, Compose derleyici eklentisi ve
    // :core aynı sürümü kullanır.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
