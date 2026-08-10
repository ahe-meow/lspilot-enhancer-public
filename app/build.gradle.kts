plugins {
    id("com.android.application")
}

android {
    namespace = "dev.operit.lspilot.enhancer"
    compileSdk = 29

    defaultConfig {
        // Legacy ID retained so 1.2.0 upgrades the previously installed module.
        applicationId = "dev.operit.lspilot.cache"
        minSdk = 26
        targetSdk = 29
        versionCode = 58
        versionName = "1.7.4-preview.21"
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
    implementation("org.luckypray:dexkit:2.2.0")
}