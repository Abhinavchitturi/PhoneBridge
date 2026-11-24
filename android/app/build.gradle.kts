plugins {
    id("com.android.application")
}

android {
    namespace = "app.phonebridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.phonebridge"
        minSdk = 33
        // Kept at 36 on purpose: Android 17 (37) adds a local-network permission and delays OTP SMS.
        targetSdk = 36
        versionCode = 25
        versionName = "0.25"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same key as the builds already on the phone, so updates install over them without uninstalling.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Document scanner for "Scan to Mac" (runs inside Google Play services; no camera permission needed)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    testImplementation("junit:junit:4.13.2")
}
