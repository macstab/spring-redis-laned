plugins {
    id("me.champeau.jmh") version "0.7.2"
}

description = "JMH benchmarks and load tests for laned Redis connections"

dependencies {
    // Core library
    implementation(project(":redis-laned-core"))
    
    // Spring Data Redis + Lettuce
    implementation("org.springframework.data:spring-data-redis:3.2.2")
    implementation("io.lettuce:lettuce-core:6.3.1.RELEASE")
    
    // JMH
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    
    // Embedded Redis for testing
    testImplementation("it.ozimov:embedded-redis:0.7.3") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic:1.4.14")
}

jmh {
    jmhVersion.set("1.37")
    
    // Benchmark configuration
    warmupIterations.set(2)
    iterations.set(5)
    fork.set(1)
    threads.set(4)
    
    // Output
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
}
