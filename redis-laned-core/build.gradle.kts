description = "Pure Lettuce core library for laned Redis connections (NO Spring dependencies)"

dependencies {
    // ONLY Lettuce - NO Spring dependencies
    compileOnly("io.lettuce:lettuce-core:6.7.1.RELEASE")
    
    // Test dependencies
    testImplementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    testImplementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Test utilities (Redis + Sentinel annotations)
    testImplementation(project(":redis-laned-test-utils"))
    
    // Testcontainers for integration tests
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}
