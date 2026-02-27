plugins {
    id("org.springframework.boot") version "4.0.3"
}

description = "Usage examples for laned Redis connections"

dependencies {
    // Spring Boot 3 Starter (brings in core transitively)
    implementation(project(":redis-laned-spring-boot-3-starter"))
    
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic:1.4.14")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("boot")
}

// Disable plain jar (only boot jar needed for examples)
tasks.named<Jar>("jar") {
    enabled = false
}
