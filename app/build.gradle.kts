plugins {
    id("com.android.application")
}

android {
    namespace = "dev.mike.fieronav"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mike.fieronav"
        minSdk = 30
        targetSdk = 30
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key on purpose: installs over debug builds and shares FlipSpot's
            // signature (needed if the two apps ever talk through a signature permission).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 30 is deliberate: the TCL Flip 2 runs Android 11 and this is never shipped to Play.
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

// No runtime dependencies: framework Views and APIs only. JUnit is test-only and never ships.
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for parser tests (Android ships its own)
}
