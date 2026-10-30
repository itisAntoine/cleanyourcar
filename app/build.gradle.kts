plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cleanyourcar.launcher"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cleanyourcar.launcher"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            assets.directories.add(rootProject.file("profiles/devices").path)
        }
        getByName("test") {
            resources.directories.add(rootProject.file("profiles/fixtures").path)
        }
    }
}

dependencies {
    // implementation(libs.appcompat)
    // implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    // androidTestImplementation(libs.espresso.core)
    // androidTestImplementation(libs.ext.junit)
}