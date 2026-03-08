/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

java {
    sourceCompatibility = JavaVersion.valueOf("VERSION_${findProperty("javaVersion")}")
    targetCompatibility = JavaVersion.valueOf("VERSION_${findProperty("javaVersion")}")
}

dependencies {
    // JUnit Jupiter API (execution conditions, extensions)
    api("org.junit.jupiter:junit-jupiter-api:${findProperty("junitVersion")}")
    
    // Testcontainers (shared container infrastructure)
    api("org.testcontainers:testcontainers:1.20.4")
    api("org.testcontainers:junit-jupiter:1.20.4")
    
    // Spring Test (optional, for @LanedRedisTest Spring integration)
    compileOnly("org.springframework:spring-test:6.2.5")
    compileOnly("org.springframework.boot:spring-boot-test:3.4.0")
    compileOnly("org.springframework.data:spring-data-redis:3.4.5")
    
    // Redis client (for auto-configuration)
    compileOnly("io.lettuce:lettuce-core:6.5.4.RELEASE")
    
    // Core module (for LanedConnectionManager)
    compileOnly(project(":redis-laned-core"))
    
    // Lombok
    compileOnly("org.projectlombok:lombok:${findProperty("lombokVersion")}")
    annotationProcessor("org.projectlombok:lombok:${findProperty("lombokVersion")}")
    
    // Logging
    api("org.slf4j:slf4j-api:${findProperty("slf4jVersion")}")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-parameters",
            "-Xlint:unchecked",
            "-Xlint:deprecation"
        )
    )
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Publishing configured in root build.gradle.kts
