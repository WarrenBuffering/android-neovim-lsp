dependencies {
    implementation(project(":protocol"))
    implementation(project(":analysis"))
    implementation(project(":diagnostics"))
    implementation(project(":completion"))
    implementation(project(":index"))
    implementation(project(":formatting"))
    implementation(project(":refactor"))
    implementation(project(":workspace"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
}
