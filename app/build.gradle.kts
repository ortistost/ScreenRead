plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.example.listcounter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.listcounter"
        minSdk = 30
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.$runNumber"
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/app.jks")
            storePassword = "android"
            keyAlias = "app"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.0")
}
