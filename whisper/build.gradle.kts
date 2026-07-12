import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

val whisperModel = layout.projectDirectory.file("src/main/assets/ggml-tiny-q5_1.bin")
val whisperModelSha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"

android {
    namespace = "com.soma.whisper"
    compileSdk = 37
    // r28 emits 16 KiB-compatible LOAD alignment for modern Android devices.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden", "-fvisibility-inlines-hidden")
                arguments += listOf(
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources.noCompress += "bin"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

val verifyWhisperModel = tasks.register("verifyWhisperModel") {
    group = "verification"
    description = "Verifies the bundled, reviewed multilingual tiny Q5 model."
    inputs.file(whisperModel)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        whisperModel.asFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte: Byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        check(actual == whisperModelSha256) {
            "Bundled Whisper model checksum mismatch: $actual"
        }
    }
}

tasks.named("preBuild").configure { dependsOn(verifyWhisperModel) }
