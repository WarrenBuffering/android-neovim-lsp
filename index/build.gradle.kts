dependencies {
    implementation(project(":protocol"))
    implementation(project(":analysis"))
    implementation(project(":workspace"))
    implementation(project(":project-import"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.4.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
}
