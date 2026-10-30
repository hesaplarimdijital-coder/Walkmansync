plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.walkmansync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.walkmansync"
        // desktop-kit 2 Android 14 ister (MIGRATION.md); Tab S11 Ultra ve Galaxy S24 Android 16'da.
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.desktop.kit)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    // Doğrudan import edilir (createSavedStateHandle, NavHostController); geçişli gelseler de sürüm sabitlensin.
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.navigation.compose)
    implementation(libs.core)
    implementation(libs.documentfile)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
