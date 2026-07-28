import java.security.MessageDigest
import java.util.Properties

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.cpp)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDir = layout.projectDirectory.dir("src/main/assets")
val geoFilesManifest = rootProject.layout.projectDirectory.file("gradle/geodata.properties")
val geoFilesProperties = Properties().apply {
    geoFilesManifest.asFile.inputStream().use { load(it) }
}
val geoFiles = (0 until geoFilesProperties.getProperty("file.count").toInt()).associate { index ->
    val outputName = geoFilesProperties.getProperty("file.$index.output")
    outputName to geoFilesProperties.getProperty("file.$index.sha256")
}

val verifyGeoFiles by tasks.registering {
    group = "verification"
    description = "Verifies the pinned geodata files bundled with the app."

    inputs.file(geoFilesManifest)
    geoFiles.forEach { (outputName, _) ->
        inputs.file(geoFilesDir.file(outputName))
            .withPropertyName(outputName)
            .withPathSensitivity(PathSensitivity.NONE)
    }

    doLast {
        geoFiles.forEach { (outputName, expectedSha256) ->
            val geoFile = geoFilesDir.file(outputName).asFile
            check(geoFile.isFile) {
                "Missing pinned geodata file $geoFile. Run scripts/update-geodata.sh and commit its output."
            }

            val digest = MessageDigest.getInstance("SHA-256")
            geoFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualSha256 == expectedSha256) {
                "SHA-256 mismatch for $geoFile: expected $expectedSha256, got $actualSha256"
            }
        }
    }
}

tasks.configureEach {
    // APK and AAB packaging must fail before consuming missing or modified geodata.
    if (name.startsWith("assemble") || name.startsWith("bundle")) {
        dependsOn(verifyGeoFiles)
    }
}
