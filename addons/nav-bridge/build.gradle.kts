plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cleanyourcar.navbridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cleanyourcar.navbridge"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    testImplementation(libs.junit)
}
