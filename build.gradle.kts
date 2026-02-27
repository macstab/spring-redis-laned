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
        }
        testLogging {
            events("started", "passed", "skipped", "failed")
            showStandardStreams = false
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
            // Only configure if credentials are available
            val githubActor = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
            val githubToken = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String?
            
            if (githubActor != null && githubToken != null) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/macstab/spring-redis-laned")
                    credentials {
                        username = githubActor
                        password = githubToken
                    }
                }
            }
            
            // Maven Central (manual release only, requires ~/.gradle/gradle.properties)
            val ossrhUsername = project.findProperty("ossrhUsername") as String?
            val ossrhPassword = project.findProperty("ossrhPassword") as String?
            
            if (ossrhUsername != null && ossrhPassword != null) {
                maven {
                    name = "OSSRH"
                    val releasesRepoUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                    val snapshotsRepoUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
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
