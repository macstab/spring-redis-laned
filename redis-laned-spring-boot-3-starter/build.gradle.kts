description = "Spring Boot 3.x starter for laned Redis connections"

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    // Core library (pure Lettuce)
    api(project(":redis-laned-core"))
    
    // Spring Boot 3.x
    api("org.springframework.boot:spring-boot-starter-data-redis:3.5.11")
    
    // Spring Boot autoconfiguration
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.5.11")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor:3.5.11")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.5.11")
    
    // Test - Metrics module (for integration tests)
    testImplementation(project(":redis-laned-metrics"))
    testImplementation("io.micrometer:micrometer-core:1.12.0")
    
    // Test - Spring Boot
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.11")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.15")
    
    // Test - Testcontainers
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    
    // Test - Redis Testcontainers (generic container for Sentinel/custom setups)
    testImplementation("com.redis.testcontainers:testcontainers-redis:1.6.4")
    
    // Test - Utilities
    testImplementation("org.awaitility:awaitility:4.2.2")
    
    // Test - Mockito (unit tests)
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
}

// Separate fast unit tests from slow integration tests
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Integration test task (includes all tests)
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (includes Testcontainers, slower)"
    group = "verification"
    
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    
    useJUnitPlatform {
        includeTags("integration")
    }
    
    shouldRunAfter(tasks.test)
}
