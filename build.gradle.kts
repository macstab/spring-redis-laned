plugins {
    `java-library`
    `maven-publish`
    signing
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.springframework.boot") version "4.0.3" apply false
    id("com.diffplug.spotless") version "8.2.1" apply false
}

group = project.property("group").toString()
version = project.property("version").toString()

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(project.property("javaVersion").toString().toInt()))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf(
            "-parameters",
            "-Xlint:unchecked",
            "-Xlint:deprecation"
        ))
    }
    
    tasks.withType<JavaCompile>().configureEach {
        if (name == "compileTestJava") {
            // Suppress unavoidable warnings in test code (tests for deprecated APIs, Mockito mocks)
            options.compilerArgs.removeAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
            options.compilerArgs.add("-Xlint:-removal")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform {
            // Support excluding tests by tag via system property (e.g., -Dtest.excludeTags=ci-excluded)
            val excludeTags = System.getProperty("test.excludeTags")
            if (!excludeTags.isNullOrBlank()) {
                excludeTags.split(",").forEach { tag ->
                    excludeTags(tag.trim())
                }
            }
            
            // Support including tests by tag via system property (e.g., -Dtest.includeTags=integration)
            val includeTags = System.getProperty("test.includeTags")
            if (!includeTags.isNullOrBlank()) {
                includeTags.split(",").forEach { tag ->
                    includeTags(tag.trim())
                }
            }
        }
        testLogging {
            events("started", "passed", "skipped", "failed")
            showStandardStreams = false
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        
        // Configure Testcontainers for Docker-in-Docker (DinD) environments
        // Uses jvmArgs to set properties BEFORE any classes load
        val dockerEnvFile = file("/.dockerenv")
        if (dockerEnvFile.exists()) {
            jvmArgs(
                "-Ddocker.host=unix:///var/run/docker.sock",
                "-Dtestcontainers.ryuk.disabled=true",
                "-DDOCKER_HOST=unix:///var/run/docker.sock",
                "-DTESTCONTAINERS_RYUK_DISABLED=true",
                "-DDOCKER_API_VERSION=1.44",
                "-Dapi.version=1.44",
                "-Djava.util.logging.config.file=${project.file("src/test/resources/logging.properties").absolutePath}"
            )
            environment("DOCKER_HOST", "unix:///var/run/docker.sock")
            environment("TESTCONTAINERS_RYUK_DISABLED", "true")
            environment("DOCKER_API_VERSION", "1.44")
            systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
            systemProperty("org.slf4j.simpleLogger.log.org.testcontainers", "debug")
            systemProperty("org.slf4j.simpleLogger.log.com.github.dockerjava", "debug")
            doFirst {
                println("✓ DinD detected - Testcontainers JVM args configured")
            }
        }
    }

    // Spotless code formatting
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            
            // Google Java Format
            googleJavaFormat(project.property("googleJavaFormatVersion").toString())
            
            // Import order (standard Java conventions)
            importOrder("java", "javax", "org", "com", "de", "")
            
            // Remove unused imports
            removeUnusedImports()
            
            // Trim trailing whitespace
            trimTrailingWhitespace()
            
            // End files with newline
            endWithNewline()
            
            // License header (2026 = year of creation, stays fixed forever!)
            // DO NOT use dynamic year - copyright is from creation year, not current year
            licenseHeader("/* (C)2026 Christian Schnapka / Macstab GmbH */")
        }
    }

    dependencies {
        val lombokVersion = project.property("lombokVersion").toString()
        val slf4jVersion = project.property("slf4jVersion").toString()
        val junitVersion = project.property("junitVersion").toString()
        val assertjVersion = project.property("assertjVersion").toString()
        val awaitilityVersion = project.property("awaitilityVersion").toString()
        val mockitoVersion = project.property("mockitoVersion").toString()

        // Lombok for all modules
        compileOnly("org.projectlombok:lombok:$lombokVersion")
        annotationProcessor("org.projectlombok:lombok:$lombokVersion")
        testCompileOnly("org.projectlombok:lombok:$lombokVersion")
        testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")

        // SLF4J API
        implementation("org.slf4j:slf4j-api:$slf4jVersion")

        // Test dependencies
        testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testImplementation("org.assertj:assertj-core:$assertjVersion")
        testImplementation("org.awaitility:awaitility:$awaitilityVersion")
        testImplementation("org.mockito:mockito-core:$mockitoVersion")
        testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    }

    // Publishing configuration (only for library modules, not examples/benchmarks/tests)
    val excludedFromPublishing = listOf(
        "redis-laned-examples",
        "redis-laned-benchmarks",
        "redis-laned-load-tests"
    )
    
    if (project.name !in excludedFromPublishing) {
        publishing {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])

                pom {
                    name.set(project.name)
                    description.set("N fixed multiplexed Redis connections with round-robin dispatch - reduces head-of-line blocking")
                    url.set("https://github.com/macstab/spring-redis-laned")
                    
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    
                    developers {
                        developer {
                            id.set("cschnapka")
                            name.set("Christian Schnapka")
                            email.set("christian.schnapka@macstab.com")
                            organization.set("Macstab GmbH")
                            organizationUrl.set("https://macstab.com")
                        }
                    }
                    
                    scm {
                        connection.set("scm:git:git://github.com/macstab/spring-redis-laned.git")
                        developerConnection.set("scm:git:ssh://github.com/macstab/spring-redis-laned.git")
                        url.set("https://github.com/macstab/spring-redis-laned")
                    }
                }
            }
        }
        
        repositories {
            // GitHub Packages (for CI and manual SNAPSHOT publishing)
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/macstab/spring-redis-laned")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
                    password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String?
                }
            }
            
            // Maven Central (manual release only, requires ~/.gradle/gradle.properties)
            val ossrhUsername = project.findProperty("ossrhUsername") as String?
            val ossrhPassword = project.findProperty("ossrhPassword") as String?
            
            if (ossrhUsername != null && ossrhPassword != null) {
                maven {
                    name = "OSSRH"
                    val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                    val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                    url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
                    credentials {
                        username = ossrhUsername
                        password = ossrhPassword
                    }
                }
            }
            }
        }
        
        // Sign artifacts for Maven Central (only if GPG configured in ~/.gradle/gradle.properties)
        signing {
            val signingKeyId = project.findProperty("signing.keyId") as String?
            val signingPassword = project.findProperty("signing.password") as String?
            val signingSecretKeyRingFile = project.findProperty("signing.secretKeyRingFile") as String?
            
            if (signingKeyId != null && signingPassword != null && signingSecretKeyRingFile != null) {
                sign(publishing.publications["maven"])
            }
        }
        
        tasks.withType<Sign>().configureEach {
            onlyIf { !version.toString().endsWith("SNAPSHOT") }
        }
    } // End of isPublishable
}

// Task to show instructions for releasing to Maven Central
tasks.register("releaseToCentral") {
    group = "publishing"
    description = "Publish staged deployment to Maven Central via API"

    doLast {
        val username = project.findProperty("ossrhUsername") as String?
        val password = project.findProperty("ossrhPassword") as String?

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            logger.warn("┌─────────────────────────────────────────────────────────────┐")
            logger.warn("│  ⚠️  Maven Central credentials not configured!              │")
            logger.warn("├─────────────────────────────────────────────────────────────┤")
            logger.warn("│  Add to ~/.gradle/gradle.properties:                        │")
            logger.warn("│  ossrhUsername=<your-sonatype-token-username>               │")
            logger.warn("│  ossrhPassword=<your-sonatype-token-password>               │")
            logger.warn("└─────────────────────────────────────────────────────────────┘")
            throw GradleException("Maven Central credentials missing. Cannot release.")
        }

        logger.lifecycle("┌─────────────────────────────────────────────────────────────┐")
        logger.lifecycle("│  🚀 Publishing to Maven Central                             │")
        logger.lifecycle("├─────────────────────────────────────────────────────────────┤")
        logger.lifecycle("│  Group: ${project.group}                                    │")
        logger.lifecycle("│  Version: ${project.version}                                │")
        logger.lifecycle("└─────────────────────────────────────────────────────────────┘")

        logger.lifecycle("📤 Triggering Central Portal manual upload API...")

        val apiUrl = "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/com.macstab"

        val process = ProcessBuilder(
            "curl", "-u", "$username:$password",
            "-X", "POST",
            apiUrl
        ).inheritIO().start()

        val exitCode = process.waitFor()

        if (exitCode == 0) {
            logger.lifecycle("")
            logger.lifecycle("┌─────────────────────────────────────────────────────────────┐")
            logger.lifecycle("│  ✅ Successfully triggered publish to Maven Central!        │")
            logger.lifecycle("├─────────────────────────────────────────────────────────────┤")
            logger.lifecycle("│  Artifacts will sync to Maven Central in ~10-30 minutes     │")
            logger.lifecycle("│  Check: https://central.sonatype.com/artifact/${project.group}")
            logger.lifecycle("└─────────────────────────────────────────────────────────────┘")
            logger.lifecycle("")
        } else {
            logger.error("Failed to publish. Manual fallback:")
            logger.error("1. Go to: https://central.sonatype.com/")
            logger.error("2. Navigate to: Deployments")
            logger.error("3. Find: ${project.group}")
            logger.error("4. Click: Publish")
            throw GradleException("Failed to publish to Maven Central (exit code: $exitCode)")
        }
    }
}
