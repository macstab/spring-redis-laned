description = "Pure Lettuce core library for laned Redis connections (NO Spring dependencies)"

dependencies {
    // ONLY Lettuce - NO Spring dependencies
    compileOnly("io.lettuce:lettuce-core:6.7.1.RELEASE")
    
    // Test dependencies
    testImplementation("io.lettuce:lettuce-core:6.7.1.RELEASE")
    testImplementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Testcontainers for integration tests
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
}
