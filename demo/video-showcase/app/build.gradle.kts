plugins {
    application
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":interop"))
    implementation(project(":network"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application {
    mainClass = "demo.video.app.AppEntryKt"
}

kotlin {
    jvmToolchain(21)
}
