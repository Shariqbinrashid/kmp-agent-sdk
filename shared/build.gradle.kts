import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinx.serialization)
}

group = "com.shariqbinrashid"
version = "1.0.0"

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Ktor for HTTP and SSE
            api(libs.ktor.client.core)
            api(libs.ktor.client.cio)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
            api(libs.ktor.client.logging)
            api(libs.ktor.client.auth)
            
            // Serialization
            api(libs.kotlinx.serialization.json)
            
            // Coroutines
            api(libs.kotlinx.coroutines.core)
            
            // DateTime
            api(libs.kotlinx.datetime)
            
            // UUID
            api(libs.uuid)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
    
    // Configure publications for all targets
    androidTarget {
        publishLibraryVariants("release")
    }
}

android {
    namespace = "com.shariqbinrashid.kmp_agent_sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
