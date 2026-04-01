plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "com.krogie"
version = "2.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0-RC")

    // Discord
    implementation("net.dv8tion:JDA:6.2.0")
    implementation("club.minnced:jda-ktx:0.14.0")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    runtimeOnly("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    runtimeOnly("org.jetbrains.exposed:exposed-dao:0.61.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:7.0.2")

    // Config
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.24")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.krogie.betterghast.MainKt")
}