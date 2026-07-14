plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.soma.media"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
