plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.s2s.agent.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                // No explicit groupId/artifactId — JitPack single-module
                // convention (see s2s-tools/core/build.gradle.kts).
                version = project.findProperty("VERSION_NAME")?.toString() ?: "0.1.0"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.github.loyality7:speech-to-speech-mobile:1.0.5")

    // Only the core contracts — a host supplies whichever s2s-llm backend,
    // s2s-context implementation and s2s-tools/custom Tools it wants. The
    // harness depends on the LanguageModel/ContextEngine/Tools interfaces
    // (published by speech-to-speech-mobile), never on a specific plugin.

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // AgentRuntime drives a real S2SEngine (speakAssistantText/sessionId) —
    // same reason core itself added Robolectric for SingleShotGenerationTest.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
