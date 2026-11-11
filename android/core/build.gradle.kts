plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.cipherkeep.core"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    lint {
        lintConfig = rootProject.file("lint.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // JNA Android native libs live in the AAR; the plain JAR lacks libjnidispatch.so.
    api("net.java.dev.jna:jna:5.19.1@aar")
    // The Android AAR carries Android .so files; host JVM tests need the desktop JNA jar.
    testImplementation("net.java.dev.jna:jna:5.19.1")
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    val hostDir = System.getenv("CK_FFI_HOST_DIR")
    if (hostDir != null) {
        systemProperty("jna.library.path", hostDir)
    }
}
