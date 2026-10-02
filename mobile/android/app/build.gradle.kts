plugins {
  id("com.android.application")
}

android {
  namespace = "ai.tennisvision.camera"
  compileSdk = 37

  defaultConfig {
    applicationId = "ai.tennisvision.camera"
    minSdk = 26
    targetSdk = 37
    versionCode = 7
    versionName = "0.5.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      // Preserve update compatibility with the APKs already delivered for local use.
      signingConfig = signingConfigs.getByName("debug")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }
  buildFeatures { buildConfig = true }
  testOptions { unitTests.isIncludeAndroidResources = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin {
    jvmToolchain(17)
  }
}

dependencies {
  implementation("androidx.appcompat:appcompat:1.8.0")
  implementation("androidx.constraintlayout:constraintlayout:2.2.2")
  // The decoder ships in the APK; no Play Services scanner module is required.
  implementation("com.journeyapps:zxing-android-embedded:4.3.0")
  implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.17")
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("androidx.test:rules:1.7.0")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
