plugins { id("com.android.application") }
android {
    namespace = "org.ambientflow.tv"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig { applicationId = "org.ambientflow.tv"; minSdk = 26; targetSdk = 32; versionCode = 1; versionName = "1.0"; testInstrumentationRunner = "android.test.InstrumentationTestRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
