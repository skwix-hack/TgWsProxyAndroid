plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose"); id("com.chaquo.python") }
android {
 namespace = "dev.skwix.tgws"
 compileSdk = 35
 defaultConfig {
  applicationId = "dev.skwix.tgws"
  minSdk = 26
  targetSdk = 35
  versionCode = 2
  versionName = "0.2.0"
  ndk { abiFilters += listOf("arm64-v8a") }
 }
 buildTypes {
  getByName("release") {
   isMinifyEnabled = true
   isShrinkResources = true
   signingConfig = signingConfigs.getByName("debug")
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  }
 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
chaquopy { defaultConfig { version = "3.12"; pip { install("cryptography==42.0.8"); install("httpx[http2]==0.28.1"); install("certifi") } } }
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.04.01"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
 implementation("androidx.core:core-ktx:1.16.0")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("io.coil-kt:coil-gif:2.7.0")
}
