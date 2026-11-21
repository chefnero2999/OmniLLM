plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// DEFAULT: package the checked-in core/src/main/jniLibs/arm64-v8a/libomnillm.so and never invoke CMake.
// An on-device IDE cannot easily pass -P flags, and its bundled CMake launcher fails under mksh
// ("cmake[1]: syntax error: unexpected ')'"), so "compile the engine yourself" must be the opt-in
// path, not the default one.
//
//   default                       -> prebuilt .so, no NDK needed
//   -Pomni.usePrebuilt=false      -> compile from source (needs scripts/fetch_llama.sh + working cmake)
val usePrebuilt = (project.findProperty("omni.usePrebuilt") as String?)?.toBoolean() ?: true
// Standard AGP location: picked up automatically, no sourceSets/jniLibs wiring required.
val prebuiltSo = file("src/main/jniLibs/arm64-v8a/libomnillm.so")

android {
    namespace = "com.omni.llm.core"
    compileSdk = 35

    // Only require the NDK when we are actually cross-compiling. A prebuilt build must work on a
    // device that has no NDK installed at all (~600 MB it does not need to download).
    if (!usePrebuilt) ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 28

        if (!usePrebuilt) externalNativeBuild {
            cmake {
                // arm64-v8a is the real target; armeabi-v7a keeps 32-bit-only devices alive with
                // the 0.5B models. x86_64 is here so the emulator can be used for UI work.
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                val vulkan = (project.findProperty("omni.vulkan") as String? ?: "false").toBoolean()
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    // Off by default; flip with -Pomni.vulkan=true once CPU-only is proven stable.
                    "-DOMNI_VULKAN=$vulkan",
                    "-DOMNI_KLEIDIAI=${(project.findProperty("omni.kleidiai") as String? ?: "false")}"
                )
                // ggml-vulkan needs the C++ Vulkan headers and SPIRV-Headers, neither of which ship
                // in the NDK. Run scripts/fetch_vulkan_headers.sh first, or point these properties
                // at your own checkouts. Without them the configure step fails with a clear message.
                if (vulkan) {
                    val repoRoot = rootProject.projectDir.absolutePath
                    val vkHeaders = (project.findProperty("omni.vulkanHeadersDir") as String?)
                        ?: "$repoRoot/third_party/vulkan-headers/include"
                    val spirvHeaders = (project.findProperty("omni.spirvHeadersDir") as String?)
                        ?: "$repoRoot/third_party/spirv-headers/include"
                    val spirvCfg = (project.findProperty("omni.spirvConfigDir") as String?)
                        ?: "$repoRoot/third_party/spirv-cfg"
                    arguments += listOf(
                        "-DOMNI_VULKAN_HEADERS_DIR=$vkHeaders",
                        "-DOMNI_SPIRV_HEADERS_DIR=$spirvHeaders",
                        "-DSPIRV-Headers_DIR=$spirvCfg",
                        // glslc turns the shaders into SPIR-V; it is in the NDK, not the PATH.
                        "-DVulkan_GLSLC_EXECUTABLE=${android.ndkDirectory}/shader-tools/" +
                            "linux-x86_64/glslc"
                    )
                }
            }
        }

        // Default: all three ABIs. -Pomni.abis=arm64-v8a builds only the 64-bit slice, which is
        // ~3x faster to compile and is what you want for day-to-day development.
        ndk {
            if (usePrebuilt) {
                // Only arm64 is prebuilt; shipping an ABI filter for anything else produces an
                // APK that installs and then crashes on System.loadLibrary.
                abiFilters += "arm64-v8a"
            } else {
                val requested = (project.findProperty("omni.abis") as String?)?.split(",")?.map { it.trim() }
                abiFilters += requested?.filter { it.isNotBlank() }
                    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    // Compiling ggml for three ABIs is the slowest part of the build. If you only care about
    // modern phones, delete armeabi-v7a and x86_64 from abiFilters above to roughly cut it in 3.
    if (!usePrebuilt) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    // Fail loudly at build time if the library went missing, instead of producing an APK that
    // installs fine and then dies on System.loadLibrary("omnillm").
    tasks.matching { it.name.contains("JniLibFolders") && it.name.startsWith("merge") }.configureEach {
        doFirst {
            if (usePrebuilt && !prebuiltSo.exists()) {
                throw GradleException(
                    "core/src/main/jniLibs/arm64-v8a/libomnillm.so is missing, and -Pomni.usePrebuilt defaults to true. " +
                        "Download the 2.4 MB project archive that contains it, or build it with " +
                        "scripts/build_native_termux.sh, or compile from source with " +
                        "-Pomni.usePrebuilt=false after running scripts/fetch_llama.sh."
                )
            }
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = false // JNI name lookups + Compose; enable after testing your own proguard file
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    // Ship the native lib uncompressed so the loader can mmap it straight from the APK.
    // `useLegacyPackaging` exists from Gradle 8.0 on; the wrapper and all current AGP releases are
    // well past that, and `false` is the default anyway.
    packaging {
        jniLibs {
            useLegacyPackaging = false
            if (usePrebuilt) {
                // The prebuilt .so is already stripped, and AGP's strip task needs the NDK to run.
                // Skipping it means packaging the prebuilt engine does NOT require an NDK install.
                keepDebugSymbols += "**/*.so"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        prefab = false
    }

}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
