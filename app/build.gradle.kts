plugins {
    id("com.android.application")
}

android {
    namespace = "com.lspilot.enhancer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lspilot.enhancer"
        minSdk = 26
        targetSdk = 29
        versionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 1
        versionName = project.findProperty("versionName")?.toString() ?: "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("org.luckypray:dexkit:2.2.0")
    compileOnly(files("../lib/libxposed-api-102.0.0.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
