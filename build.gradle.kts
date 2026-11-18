plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

val singleHttpAttempt = "-Djdk.httpclient.redirects.retrylimit=1"

application {
    mainClass = "aireviewer.MainKt"
    applicationDefaultJvmArgs = listOf(singleHttpAttempt)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(singleHttpAttempt)
}
