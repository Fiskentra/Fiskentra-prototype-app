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
val maptilerApiKey = localProperties.getProperty("MAPTILER_API_KEY", "")

android {
    namespace = "com.fiskentra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fiskentra.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "0.25"
        buildConfigField("String", "GEOCODING_URL", buildConfigString(localProperties.getProperty("GEOCODING_URL", "https://api.maptiler.com/geocoding/")))
        buildConfigField("String", "ROUTING_URL", buildConfigString(localProperties.getProperty("ROUTING_URL", "https://valhalla1.openstreetmap.de/route")))

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
    implementation("com.github.50ButtonsEach:flic2lib-android:2.0.1")
    implementation("org.maplibre.gl:android-sdk:13.4.1")
}
