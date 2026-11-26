plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// Permanent release key (same variables as PT2VHF Speed Alert). Without it, local builds
// fall back to the debug key, which cannot update an APK signed with the release key.
val releaseKeyStore = System.getenv("PT2VHF_SIGNING_STORE_FILE")
android {
    namespace = "com.pt2vhf.sondefinder"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.pt2vhf.sondefinder"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.4.1"
    }
    signingConfigs {
        if (!releaseKeyStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeyStore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (!releaseKeyStore.isNullOrBlank()) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
