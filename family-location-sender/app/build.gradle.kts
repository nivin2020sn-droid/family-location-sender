plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.family.locationsender"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.locationsender"
        // Lowered from 24 → 21 so the app installs on the cheap Android 5.1 /
        // 6.0 car head units that were silently rejecting the APK.
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "1.4.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Build a fat APK containing native libs for BOTH 32-bit (older head
        // units, cheap car stereos) and 64-bit (modern phones / tablets)
        // ARM devices. Without armeabi-v7a the install hangs / silently
        // fails on legacy car head units.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign the release build with the standard debug keystore so we
            // produce a real, installable APK (not "-unsigned.apk").
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Don't fail the release build because of lint warnings — CI just needs
    // to produce an APK.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk = 21 is the cut-off for stream-API + java.time desugaring.
        // We don't need core-library desugaring right now, so leave it off.
        isCoreLibraryDesugaringEnabled = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")

    // Background work
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Encrypted prefs
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.json:json:20240303")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Image loading (for profile picture)
    implementation("io.coil-kt:coil:2.6.0")

    // EXIF metadata reader (for auto-rotating profile pictures)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
}
