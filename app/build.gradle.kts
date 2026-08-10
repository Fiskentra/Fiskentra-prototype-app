import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val supabaseUrl = localProperties.getProperty(
    "SUPABASE_URL",
    "https://dwlbefpmwzmhutlvqfmu.supabase.co"
)
val supabasePublishableKey = localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY", "")
val maptilerApiKey = localProperties.getProperty("MAPTILER_API_KEY", "Po4KLeYIgzOiUCmzXz5z")

android {
    namespace = "com.fiskentra.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.fiskentra.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(supabasePublishableKey))
        buildConfigField("String", "MAPTILER_API_KEY", buildConfigString(maptilerApiKey))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.4.1")
}
