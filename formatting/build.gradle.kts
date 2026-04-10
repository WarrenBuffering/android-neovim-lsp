dependencies {
    implementation(project(":protocol"))
    implementation(project(":analysis"))
    implementation(project(":workspace"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.4.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
