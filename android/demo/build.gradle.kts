// glimmer-android's demo.
// The Java and C are glimmer-android's, at ../glimmer; the Jolt library
// is a prebuilt the script puts in jniLibs.
plugins { id("com.android.application") version "9.4.1" }

android {
    namespace = "dev.staton.glimmer.demo"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.staton.glimmer.demo"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets {
        getByName("main") { java.srcDirs("../glimmer/src/main/java") }
    }
    externalNativeBuild {
        cmake {
            path = file("../glimmer/src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}
