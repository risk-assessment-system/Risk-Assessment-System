plugins {
    id("com.android.application")
}

android {
    namespace = "com.avsec.qaunit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avsec.qaunit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "DEFAULT_WEB_APP_URL",
            "\"https://YOUR_GITHUB_USERNAME.github.io/YOUR_REPOSITORY_NAME/\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
