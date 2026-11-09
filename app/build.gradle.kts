plugins {
    id("com.android.application")
}

android {
    namespace = "com.remotewake"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.remotewake"
        // Pixel 5 shipped with Android 11 and Pixel 6 with Android 12; Phone B can be older.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Signed with the debug key so Android Studio can install a release build on your own phones.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    // OkHttp: open-source HTTP/WebSocket client. Only code, no third-party service.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
