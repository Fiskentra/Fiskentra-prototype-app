import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
    val backendFile = rootProject.file("supabase.local.properties")
    if (backendFile.exists()) backendFile.inputStream().use(::load)
}

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val supabaseUrl = localProperties.getProperty(
    "SUPABASE_URL",
    "https://ihrguwrdicwixbimunje.supabase.co"
)
val supabasePublishableKey = localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY", "")
val maptilerApiKey = localProperties.getProperty("MAPTILER_API_KEY", "")
// Separate ignored override is useful when the SDK-managed local.properties is read-only.
val mapsLocalProperties = Properties().apply {
    val mapsFile = rootProject.file("maps.local.properties")
    if (mapsFile.exists()) mapsFile.inputStream().use(::load)
}
val googleMapsApiKey = mapsLocalProperties.getProperty("GOOGLE_MAPS_API_KEY")
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: localProperties.getProperty("GOOGLE_MAPS_API_KEY")
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable("GOOGLE_MAPS_API_KEY").orElse("").get().trim()

android {
    namespace = "com.fiskentra.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fiskentra.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 46
        versionName = "0.29.1"
        buildConfigField("String", "GEOCODING_URL", buildConfigString(localProperties.getProperty("GEOCODING_URL", "https://api.maptiler.com/geocoding/")))
        buildConfigField("String", "ROUTING_URL", buildConfigString(localProperties.getProperty("ROUTING_URL", "https://valhalla1.openstreetmap.de/route")))

        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(supabasePublishableKey))
        buildConfigField("String", "MAPTILER_API_KEY", buildConfigString(maptilerApiKey))
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", buildConfigString(googleMapsApiKey))
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey
    }

    buildFeatures {
        buildConfig = true
    }

    // The application currently supplies English through its own locale context.
    // Keep it available in every installed split when generating an app bundle.
    bundle {
        language {
            enableSplit = false
        }
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
    implementation("androidx.activity:activity:1.13.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.github.50ButtonsEach:flic2lib-android:2.0.1")
    implementation("com.google.android.gms:play-services-maps:20.1.0")
    // Retained for the previous provider's offline-area storage/cleanup only.
    implementation("org.maplibre.gl:android-sdk:13.7.1")
}
