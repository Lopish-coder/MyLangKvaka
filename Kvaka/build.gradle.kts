plugins {
    kotlin("jvm") version "2.3.21"
}

group = "org.example"
version = "0.1.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}