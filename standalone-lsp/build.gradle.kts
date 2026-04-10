dependencies {
    implementation(project(":protocol"))
    implementation(project(":workspace"))
    implementation(project(":project-import"))
    implementation(project(":analysis"))
    implementation(project(":index"))
    implementation(project(":diagnostics"))
    implementation(project(":completion"))
    implementation(project(":hover"))
    implementation(project(":symbols"))
    implementation(project(":navigation"))
    implementation(project(":refactor"))
    implementation(project(":formatting"))
    implementation(project(":code-actions"))
    rootProject.findProject(":jetbrains-bridge-plugin")?.let { bridgeProject ->
        runtimeOnly(bridgeProject)
    }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
}
