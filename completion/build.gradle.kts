dependencies {
    implementation(project(":protocol"))
    implementation(project(":analysis"))
    implementation(project(":index"))
    implementation(project(":project-import"))
    implementation(project(":workspace"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.13.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.4.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4")
    rootProject.findProject(":jetbrains-bridge-plugin")?.let { bridgeProject ->
        runtimeOnly(bridgeProject)
    }
}
