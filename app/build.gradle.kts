// Mirrors :core — see core/build.gradle.kts. Defaults to the prebuilt native library, so a plain
// `assembleDebug` from any IDE needs neither the NDK nor CMake.
val usePrebuilt = (project.findProperty("omni.usePrebuilt") as String?)?.toBoolean() ?: true

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.omni.llm"
    compileSdk = 35
    if (!usePrebuilt) ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.omni.llm"
        minSdk = 28
        targetSdk = 35
        versionCode = 33
        versionName = "3.3.0"

        // Referenced by ModelsManifest.kt. Point this at a static host (GitHub Pages, Cloudflare)
        // to refresh the model list without shipping an update. The bundled asset is used when
        // this is blank or unreachable, so offline installs still work.
        buildConfigField("String", "OMNI_MANIFEST_URL", "\"\"")

        // Default: all three ABIs. -Pomni.abis=arm64-v8a builds only the 64-bit slice, which is
        // ~3x faster to compile and is what you want for day-to-day development.
        ndk {
            if (usePrebuilt) {
                abiFilters += "arm64-v8a"
            } else {
                val requested = (project.findProperty("omni.abis") as String?)?.split(",")?.map { it.trim() }
                abiFilters += requested?.filter { it.isNotBlank() }
                    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    signingConfigs {
        // Release builds are signed with a local keystore when one is provided, otherwise with
        // the debug key so `./gradlew assembleRelease` produces an installable APK out of the box.
        create("release") {
            val storePath = (project.findProperty("omni.keystore") as String?)
                ?: System.getenv("OMNI_KEYSTORE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = (project.findProperty("omni.keystorePassword") as String?)
                    ?: System.getenv("OMNI_KEYSTORE_PASSWORD") ?: ""
                keyAlias = (project.findProperty("omni.keyAlias") as String?)
                    ?: System.getenv("OMNI_KEY_ALIAS") ?: "omnillm"
                keyPassword = (project.findProperty("omni.keyPassword") as String?)
                    ?: System.getenv("OMNI_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The checked-in libomnillm.so is already stripped. On AndroidIDE the AAPT2 strip step can
        // run the NDK's llvm-strip wrapper through a non-bash shell (mksh), which feeds it binary
        // junk and fails with "can't open \u0001..." / "syntax error: unexpected '('". The build
        // then still succeeds but warns loudly. Telling Gradle not to strip this module removes the
        // noise and any chance of a half-processed .so being packaged.
        jniLibs {
            keepDebugSymbols += "**/libomnillm.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
            )
        }
    }

    // Models live in external app storage and can be many GB: never let the package step see them.
    androidResources {
        noCompress += listOf("gguf")
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
