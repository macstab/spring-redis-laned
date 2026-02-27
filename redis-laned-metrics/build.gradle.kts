description = "Micrometer metrics for laned Redis connections (Spring Boot 3.x + 4.x compatible)"

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    // Core library
    api(project(":redis-laned-core"))
    
    // Lettuce (compileOnly - optional dependency, provided by Spring Boot)
    compileOnly("io.lettuce:lettuce-core:6.7.1.RELEASE")
    
    // Micrometer (compileOnly - optional dependency, user decides)
    compileOnly("io.micrometer:micrometer-core:1.12.0")
    
    // Spring Boot (compileOnly - provided by parent application)
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.5.11")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor:3.5.11")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.5.11")
    
    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    
    // Testing - Lettuce (testImplementation - required for tests)
    testImplementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    
    // Testing - Micrometer (testImplementation - required for tests)
    testImplementation("io.micrometer:micrometer-core:1.12.0")
    
    // Testing - Spring Boot
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.11")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis:3.5.11")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.15")
    
    // Testing - Testcontainers (for integration tests)
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
}

// Separate fast unit tests from integration tests (if any)
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Integration test task (future use)
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (if any)"
    group = "verification"
    
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    
    useJUnitPlatform {
        includeTags("integration")
    }
    
    shouldRunAfter(tasks.test)
}
