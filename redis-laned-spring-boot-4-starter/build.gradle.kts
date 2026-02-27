description = "Spring Boot 4.x starter for laned Redis connections (Java 21+ required)"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Force Netty 4.1.x (Spring Boot 4.0.3 tries to use 4.2.x which breaks Lettuce)
configurations.all {
    resolutionStrategy {
        force(
            "io.netty:netty-common:4.1.125.Final",
            "io.netty:netty-buffer:4.1.125.Final",
            "io.netty:netty-codec:4.1.125.Final",
            "io.netty:netty-handler:4.1.125.Final",
            "io.netty:netty-transport:4.1.125.Final",
            "io.netty:netty-resolver:4.1.125.Final"
        )
    }
}

dependencies {
    // Core library (pure Lettuce)
    api(project(":redis-laned-core"))
    
    // Spring Boot 4.x (requires Java 21)
    api("org.springframework.boot:spring-boot-starter-data-redis:4.0.3")
    
    // Spring Boot autoconfiguration
    implementation("org.springframework.boot:spring-boot-autoconfigure:4.0.3")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor:4.0.3")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.0.3")
    
    // Test - Metrics module (for integration tests)
    testImplementation(project(":redis-laned-metrics"))
    testImplementation("io.micrometer:micrometer-core:1.12.0")
    
    // Test - Spring Boot
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.3") {
        // Let Spring Boot 4 control JUnit version (6.0.3, not root 5.12.2)
        exclude(group = "org.junit.jupiter", module = "junit-jupiter")
    }
    // Explicitly use JUnit 6.0.3 (required by Spring Boot 4)
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.15")
    
    // Test - Testcontainers
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    
    // Test - Redis Testcontainers (generic container for Sentinel/custom setups)
    testImplementation("com.redis.testcontainers:testcontainers-redis:1.6.4")
    
    // Test - Utilities (override root versions for Spring Boot 4 compatibility)
    testImplementation("org.awaitility:awaitility:4.3.0") // Spring Boot 4 version
    testImplementation("org.mockito:mockito-core:5.20.0") // Spring Boot 4 version
    testImplementation("org.mockito:mockito-junit-jupiter:5.20.0") // Spring Boot 4 version
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
