plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.pt2vhf.sondefinder"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.pt2vhf.sondefinder"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly (sideload).
            signingConfig = signingConfigs.getByName("debug")
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
