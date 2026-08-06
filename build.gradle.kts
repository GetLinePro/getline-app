@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import java.util.*

buildscript {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
    }
}

// ABI matrix of the published APK. Every ABI costs a separate native build of
// the mihomo core (~2 min each in CI), so a build that does not need the whole
// matrix can narrow it with -Pgetline.abis=arm64-v8a.
//
// Only PR checks pass the property. Release and pre-release builds must not:
// the published APK has to carry every supported ABI. See docs/release-policy.md.
val allSupportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val supportedAbis: List<String> = (findProperty("getline.abis") as String?)
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.also { requested ->
        // A typo would otherwise produce an APK with no native core at all.
        val unknown = requested - allSupportedAbis.toSet()
        require(unknown.isEmpty()) {
            "Unknown ABI in -Pgetline.abis: $unknown. Supported: $allSupportedAbis"
        }
    }
    ?: allSupportedAbis

subprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }

    val isApp = name == "app"
    val isBundleInvocation = gradle.startParameter.taskNames.any { taskName ->
        taskName.substringAfterLast(':').startsWith("bundle", ignoreCase = true)
    }

    apply(plugin = if (isApp) "com.android.application" else "com.android.library")

    extensions.configure<BaseExtension> {
        buildFeatures.buildConfig = true
        defaultConfig {
            if (isApp) {
                applicationId = "pro.getline.vpn"
            }

            // CMFA modules use upstream namespace (slice 7a/7b). getlineui is product-only.
            // project.name — not bare name: inside defaultConfig, name is the config name "main".
            val moduleName = project.name
            namespace = when (moduleName) {
                "getlineui" -> "pro.getline.vpn.getlineui"
                "app" -> "com.github.kr328.clash"
                else -> "com.github.kr328.clash.$moduleName"
            }

            // androidx.browser 1.10+ (Auth Tab) requires minSdk 23.
            minSdk = 23
            targetSdk = 36

            // Product version, independent of the CMFA release we forked from.
            // The mihomo core reports its own version via Bridge.nativeCoreVersion().
            versionName = "0.5.1"
            versionCode = 2017

            resValue("string", "release_name", "v$versionName")
            resValue("integer", "release_code", "$versionCode")

            ndk {
                abiFilters += supportedAbis
            }

            externalNativeBuild {
                cmake {
                    abiFilters(*supportedAbis.toTypedArray())
                }
            }

            if (!isApp) {
                consumerProguardFiles("consumer-rules.pro")
            } else {
                setProperty("archivesBaseName", "getline-vpn-$versionName")
            }
        }

        ndkVersion = "29.0.14206865"
        buildToolsVersion = "36.0.0"

        // Keep compileSdk aligned with targetSdk (Play requires target 36 after 2026-08-31).
        compileSdkVersion(defaultConfig.targetSdk!!)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
                }
            }
        }

        productFlavors {
            // channel: distribution/package id only (not feature flags, not VPN core).
            // alpha = side-by-side / default local; meta = production package id.
            flavorDimensions("channel")

            create("alpha") {
                isDefault = true
                dimension = "channel"
                versionNameSuffix = ".Alpha"

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                // Use literal generated values: androidTest resource linking does not
                // include the :design resource that previously supplied these aliases.
                resValue("string", "launch_name", "GetLine Pro Alpha")
                resValue("string", "application_name", "GetLine Pro Alpha")

                if (isApp) {
                    applicationIdSuffix = ".alpha"
                }
            }

            create("meta") {
                dimension = "channel"

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                // Production channel: clean product name, no .Meta suffix.
                resValue("string", "launch_name", "GetLine Pro")
                resValue("string", "application_name", "GetLine Pro")
            }
        }

        sourceSets {
            getByName("meta") {
                java.srcDirs("src/foss/java")
            }
            getByName("alpha") {
                java.srcDirs("src/foss/java")
            }
        }

        signingConfigs {
            val signingProperties = rootProject.file(
                System.getenv("GETLINE_SIGNING_PROPERTIES")
                    ?.takeIf(String::isNotBlank)
                    ?: "signing.properties"
            )
            val signingKeystore = rootProject.file(
                System.getenv("GETLINE_SIGNING_KEYSTORE")
                    ?.takeIf(String::isNotBlank)
                    ?: "release.keystore"
            )
            if (signingProperties.exists()) {
                create("release") {
                    val prop = Properties().apply {
                        signingProperties.inputStream().use(this::load)
                    }

                    storeFile = signingKeystore
                    storePassword = prop.getProperty("keystore.password")!!
                    keyAlias = prop.getProperty("key.alias")!!
                    keyPassword = prop.getProperty("key.password")!!
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                // Never fall back to the debug key. Without signing configuration
                // the artifact is unsigned; production CI must fail if keys are missing.
                signingConfig = signingConfigs.findByName("release")
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
                if (isApp) {
                    applicationIdSuffix = ".debug"
                }
            }
        }

        buildFeatures.apply {
            dataBinding {
                isEnabled = name != "hideapi"
            }
        }

        if (isApp) {
            this as AppExtension

            splits {
                abi {
                    // AGP cannot produce legacy split APK resources and an AAB in one invocation.
                    isEnable = !isBundleInvocation
                    isUniversalApk = true
                    reset()
                    include(*supportedAbis.toTypedArray())
                }
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum =
        "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
}
