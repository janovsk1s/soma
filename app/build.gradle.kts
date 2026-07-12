import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseAbi = providers.gradleProperty("soma.releaseAbi").getOrElse("arm64-v8a")
require(releaseAbi == "arm64-v8a") {
    "Soma v1 supports the Light Phone III arm64-v8a release target (got '$releaseAbi')"
}

android {
    namespace = "com.soma.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.soma.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "network"
    productFlavors {
        create("browser") {
            dimension = "network"
            buildConfigField("Boolean", "BROWSER_VIEW_AVAILABLE", "true")
        }
        create("purist") {
            dimension = "network"
            applicationIdSuffix = ".purist"
            versionNameSuffix = "-purist"
            buildConfigField("Boolean", "BROWSER_VIEW_AVAILABLE", "false")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-development"
            resValue("string", "app_name", "Soma Development")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk { abiFilters += releaseAbi }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    androidResources {
        localeFilters += setOf("en", "lv", "et", "lt", "fi", "sv", "de", "sk")
    }

    bundle.language.enableSplit = false

    testOptions.unitTests.isIncludeAndroidResources = true

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += "MissingTranslation"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":storage"))
    implementation(project(":voice"))
    implementation(project(":whisper"))
    "browserImplementation"(project(":lanserver"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

tasks.register("verifyNoHttpClients") {
    group = "verification"
    description = "Fails if a known outbound HTTP client enters a runtime classpath."
    doLast {
        val forbidden = listOf("okhttp", "retrofit", "volley", "cronet", "ktor-client", "httpclient")
        configurations
            .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
            .forEach { configuration ->
                val found = configuration.resolvedConfiguration.resolvedArtifacts
                    .map { "${it.moduleVersion.id.group}:${it.name}" }
                    .filter { coordinate -> forbidden.any(coordinate::contains) }
                check(found.isEmpty()) {
                    "Outbound HTTP client dependency found in ${configuration.name}: ${found.joinToString()}"
                }
            }
    }
}

tasks.named("check").configure { dependsOn("verifyNoHttpClients") }
