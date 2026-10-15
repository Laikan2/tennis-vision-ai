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
    versionCode = 21
    versionName = "0.6.8"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    create("audit") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".audit"
      matchingFallbacks += listOf("debug")
    }
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
    create("auditRelease") {
      initWith(getByName("release"))
      applicationIdSuffix = ".audit"
      matchingFallbacks += listOf("release")
    }
  }
  // Explicit opt-in: device tests get their own app/data and never overwrite a personal session.
  testBuildType = if (providers.gradleProperty("auditDevice").orNull == "true") "audit" else "debug"
  buildFeatures { buildConfig = true }
  testOptions { unitTests.isIncludeAndroidResources = true }
  sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/java")
  sourceSets.getByName("test").resources.srcDir("../../shared")
  sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
  sourceSets.getByName("auditRelease").apply {
    manifest.srcFile("src/audit/AndroidManifest.xml")
    res.srcDir("src/audit/res")
  }

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
