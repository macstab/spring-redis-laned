plugins {
    id("java-library")
    id("me.champeau.jmh") version "0.7.3"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // JMH core
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    
    // Library under test
    jmh(project(":redis-laned-core"))
    
    // Testcontainers
    jmh("org.testcontainers:testcontainers:1.19.3")
    
    // Lettuce (Redis client)
    jmh("io.lettuce:lettuce-core:6.7.1.RELEASE")
    
    // Utilities
    jmh("com.google.guava:guava:33.0.0-jre")
    
    // Logging (required by Testcontainers)
    jmh("ch.qos.logback:logback-classic:1.5.15")
    jmh("org.slf4j:slf4j-api:2.0.12")
}

jmh {
    // Balanced config: Statistical confidence + practical speed
    // Total duration: ~46 minutes (17 benchmarks × 2.7 min each)
    
    // Warmup: 3 iterations × 10 seconds = 30s (stable JVM + connection pool)
    warmupIterations = 3
    warmup = "10s"
    
    // Measurement: 5 iterations × 10 seconds = 50s (stable latency samples)
    iterations = 5
    timeOnIteration = "10s"
    
    // Forks: 2 (statistical confidence without excessive runtime)
    fork = 2
    
    // Output: milliseconds (latency-focused benchmarks)
    timeUnit = "ms"
    
    // Result formats: JSON (primary) + TEXT (human-readable)
    // Note: Gradle plugin only supports single format; use -PjmhResultFormat=TEXT for text output
    resultFormat = "JSON"
    resultsFile = file("${project.layout.buildDirectory.get()}/reports/jmh/results.json")
    
    // Benchmark selection (default: all)
    includes = listOf(".*Benchmark.*")
    
    // JVM args
    jvmArgs = listOf(
        "-Xms2g",
        "-Xmx2g",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=100"
    )
}

// Task: Run JMH with JSON output (default)
tasks.named("jmh") {
    doLast {
        println("\n=== JMH Benchmark Complete ===")
        println("JSON results: ${project.layout.buildDirectory.get()}/reports/jmh/results.json")
        println("\nTo generate text output, run:")
        println("  ./gradlew jmh -PjmhResultFormat=TEXT")
    }
}

// Task: Run JMH with both JSON + TEXT output
tasks.register("jmhBoth") {
    group = "benchmark"
    description = "Run JMH benchmarks and generate both JSON + TEXT results"
    
    doLast {
        println("\n=== Running JMH with dual output (JSON + TEXT) ===")
        println("Note: Run manually with different -PjmhResultFormat parameter")
        println("  1. ./gradlew :redis-laned-benchmarks:jmh -PjmhResultFormat=JSON")
        println("  2. ./gradlew :redis-laned-benchmarks:jmh -PjmhResultFormat=TEXT -PjmhResultsFile=build/reports/jmh/results.txt")
    }
}

// Support -PjmhResultFormat parameter
if (project.hasProperty("jmhResultFormat")) {
    jmh.resultFormat = project.property("jmhResultFormat") as String
}

if (project.hasProperty("jmhResultsFile")) {
    jmh.resultsFile = file(project.property("jmhResultsFile") as String)
}
