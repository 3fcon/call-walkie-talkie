plugins {
    id("com.android.application") version "8.1.2"
    id("org.jetbrains.kotlin.android") version "1.9.0"
}

android {
    namespace = "com.bulletproof.call"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bulletproof.call"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    // Removed the BOM wrapper that crashed Gradle and added explicit versions
    implementation("androidx.compose.ui:ui:1.6.2")
    implementation("androidx.compose.material3:material3:1.2.0")
}