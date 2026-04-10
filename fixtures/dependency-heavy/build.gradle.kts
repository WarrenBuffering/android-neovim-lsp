plugins {
    kotlin("jvm") version "2.3.20"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.4.2")
    implementation("com.squareup.okio:okio-jvm:3.17.0")
}

kotlin {
    jvmToolchain(21)
}

