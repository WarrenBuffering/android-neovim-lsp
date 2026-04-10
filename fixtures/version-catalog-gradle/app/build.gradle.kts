plugins {
    alias(libs.plugins.kotlin.serde)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
}
