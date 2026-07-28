import android.databinding.tool.ext.capitalizeUS
import com.github.kr328.golang.GolangBuildTask
import com.github.kr328.golang.GolangPlugin

plugins {
    kotlin("android")
    id("com.android.library")
    id("kotlinx-serialization")
    id("golang-android")
}

val golangSource = file("src/main/golang/native")

golang {
    sourceSets {
        create("alpha") {
            // no_ssh: GetLine does not ship SSH outbound (security gate).
            tags.set(listOf("foss", "with_gvisor", "cmfa", "no_ssh"))
            srcDir.set(file("src/foss/golang"))
        }
        create("meta") {
            // no_ssh: GetLine does not ship SSH outbound (security gate).
            tags.set(listOf("foss", "with_gvisor", "cmfa", "no_ssh"))
            srcDir.set(file("src/foss/golang"))
        }
        all {
            fileName.set("libclash.so")
            packageName.set("cfa/native")
        }
    }
}

android {
    productFlavors {
        all {
            externalNativeBuild {
                cmake {
                    arguments("-DGO_SOURCE:STRING=${golangSource}")
                    arguments("-DGO_OUTPUT:STRING=${GolangPlugin.outputDirOf(project, null, null)}")
                    arguments("-DFLAVOR_NAME:STRING=$name")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.core)
    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)
}

// Parent-tracked Mihomo product patches (e.g. no_ssh). Required after clean
// submodule checkout; do not rely on a dirty clash working tree.
// Always run: submodule update --force can leave untracked stub markers while
// resetting tracked sources; a single-file outputs check would stay UP-TO-DATE
// and skip recovery (duplicate SSH symbols at Go compile).
val applyMihomoPatches = tasks.register<Exec>("applyMihomoPatches") {
    group = "build"
    description = "Apply core/patches/mihomo/*.patch onto clash-foss submodule"
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/apply-mihomo-patches.sh")
    inputs.dir(layout.projectDirectory.dir("patches/mihomo"))
    inputs.file(rootProject.file("scripts/apply-mihomo-patches.sh"))
    outputs.upToDateWhen { false }
}

afterEvaluate {
    tasks.withType(GolangBuildTask::class.java).forEach {
        it.dependsOn(applyMihomoPatches)
        it.inputs.dir(golangSource)
        it.environment("GOFLAGS", "-buildvcs=false")
    }
}

val abis = listOf("arm64-v8a" to "Arm64V8a", "armeabi-v7a" to "ArmeabiV7a", "x86" to "X86", "x86_64" to "X8664")

androidComponents.onVariants { variant ->
    val cmakeName = if (variant.buildType == "debug") "Debug" else "RelWithDebInfo"

    abis.forEach { (abi, goAbi) ->
        tasks.configureEach {
            if (name.startsWith("buildCMake$cmakeName[$abi]")) {
                dependsOn("externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
                println("Set up dependency: $name -> externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
            }
        }
    }
}
