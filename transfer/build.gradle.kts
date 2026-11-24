plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":shared"))
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
