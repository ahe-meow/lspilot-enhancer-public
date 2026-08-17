plugins {
    id("com.android.application")
}

android {
    namespace = "com.lspilot.enhancer"
    compileSdk = 29

    defaultConfig {
        applicationId = "com.lspilot.enhancer"
        minSdk = 26
        targetSdk = 29
        versionCode = 64
        versionName = "1.7.4-preview.27"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // targetSdk 29 is retained for host compatibility; this module is not distributed through Play.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    compileOnly(files("../lib/libxposed-api-102.0.0.aar"))
}
