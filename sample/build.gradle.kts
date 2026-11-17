plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.figolabs.figochat.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.figolabs.figochat.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "FIGO_API_KEY", "\"${project.findProperty("figoApiKey") ?: ""}\"")
        buildConfigField("String", "FIGO_ASSISTANT_ID", "\"${project.findProperty("figoAssistantId") ?: ""}\"")
    }

    buildTypes {
        release {
            // shrunk like a real app (also exercises the SDK's consumer ProGuard rules); debug-signed for testing only
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":figochat"))
    implementation("androidx.appcompat:appcompat:1.7.0")
}
