import java.io.File
import java.util.Properties

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
        versionCode = 60
        versionName = "1.7.4-preview.23"
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

tasks.register("buildModelCompressionCheckDex") {
    dependsOn("compileDebugJavaWithJavac", "compileDebugUnitTestJavaWithJavac")
    doLast {
        val outputDir = rootProject.file("out/model-compression-check")
        project.delete(outputDir)
        outputDir.mkdirs()

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }
        val sdkPath = localProperties.getProperty("sdk.dir")
                ?: System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: throw GradleException("Android SDK path is unavailable")
        val sdkDirectory = File(sdkPath)
        val d8 = File(sdkDirectory, "build-tools/36.0.0/d8")
        val androidJar = File(sdkDirectory, "platforms/android-29/android.jar")
        val mainClasses = file(
                "build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar")
        val unitTestClasses = file("build/tmp/model-compression-check/unit-tests.jar")
        project.delete(unitTestClasses)
        ant.withGroovyBuilder {
            "jar"("destfile" to unitTestClasses.absolutePath,
                    "basedir" to file(
                            "build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes")
                            .absolutePath)
        }

        val command = mutableListOf(d8.absolutePath, "--lib", androidJar.absolutePath,
                "--output", outputDir.absolutePath)
        command.addAll(listOf(mainClasses, unitTestClasses).map { it.absolutePath })
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { println(it) } }
        val exitCode = process.waitFor()
        if (exitCode != 0) throw GradleException("d8 failed with exit code $exitCode")
    }
}
