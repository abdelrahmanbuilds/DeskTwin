plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android { namespace = "com.desktwin.app"; compileSdk = 35
    defaultConfig {
        applicationId = "com.desktwin.app"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("androidVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("androidVersionName").orNull ?: "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    sourceSets["main"].java.srcDirs("../src/main/java")
    sourceSets["main"].manifest.srcFile("../src/main/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/main/res", "../src/main/res")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    // ماسح QR جاهز من Google Play Services؛ لا يطلب إذن الكاميرا من DeskTwin.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
