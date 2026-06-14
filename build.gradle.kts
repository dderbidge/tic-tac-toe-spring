plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.derbysquad"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

springBoot {
    // `./gradlew bootRun` / `bootJar` start the multiplayer server (Tasks 5-8).
    mainClass = "com.derbysquad.tictactoe.TicTacToeApplication"
}

// `./gradlew runConsole` plays the ported console game (Task 3 vertical slice).
// A dedicated JavaExec task avoids the `application` plugin, whose `mainClass`
// otherwise clashed with Spring Boot's and made `bootRun` launch the console.
tasks.register<JavaExec>("runConsole") {
    group = "application"
    description = "Runs the ported console tic-tac-toe game."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.derbysquad.tictactoe.console.ConsoleApplication"
    standardInput = System.`in`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
