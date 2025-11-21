import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinx.serialization)
    `maven-publish`
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
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Publishing configuration for JitPack
publishing {
    publications.withType<MavenPublication> {
        // Set artifact ID - use publication name to avoid conflicts
        // This allows each target (android, ios, etc.) to have unique artifacts
        val publicationName = this.name
        artifactId = if (publicationName == "kotlinMultiplatform") {
            "kmp-agent-sdk"
        } else {
            "kmp-agent-sdk-$publicationName"
        }
        
        pom {
            name.set("KMP Agent SDK")
            description.set("A Kotlin Multiplatform SDK for integrating AI agents into mobile applications")
            url.set("https://github.com/Shariqbinrashid/kmp-agent-sdk")
            
            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            
            developers {
                developer {
                    id.set("shariqbinrashid")
                    name.set("Shariq Bin Rashid")
                    email.set("shariqbinrashid@gmail.com")
                }
            }
            
            scm {
                connection.set("scm:git:git://github.com/Shariqbinrashid/kmp-agent-sdk.git")
                developerConnection.set("scm:git:ssh://github.com:Shariqbinrashid/kmp-agent-sdk.git")
                url.set("https://github.com/Shariqbinrashid/kmp-agent-sdk/tree/master")
            }
        }
    }
}
