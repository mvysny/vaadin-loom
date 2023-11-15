import org.gradle.api.tasks.testing.logging.TestExceptionFormat

val vaadinVersion: String by extra

plugins {
    java
    application
    id("com.vaadin")
}

defaultTasks("clean", "build")

repositories {
    mavenCentral()
}

dependencies {
    // Vaadin
    implementation("com.vaadin:vaadin-core:$vaadinVersion") {
        afterEvaluate {
            if (vaadin.productionMode) {
                exclude(module = "vaadin-dev")
            }
        }
    }

    // Vaadin-Boot
    implementation("com.github.mvysny.vaadin-boot:vaadin-boot:12.1")

    implementation("org.jetbrains:annotations:24.0.1")

    // logging
    // currently we are logging through the SLF4J API to SLF4J-Simple. See src/main/resources/simplelogger.properties file for the logger configuration
    implementation("org.slf4j:slf4j-simple:2.0.7")

    // Fast Vaadin unit-testing with Karibu-Testing: https://github.com/mvysny/karibu-testing
    testImplementation("com.github.mvysny.kaributesting:karibu-testing-v24:2.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

application {
    mainClass = "com.vaadin.starter.skeleton.Main"
    // we are going really low-level here.
    applicationDefaultJvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

tasks.withType<Test> {
    jvmArgs(listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED"))
}

tasks.withType<JavaExec> {
    jvmArgs(listOf("--enable-preview", "--add-opens", "java.base/java.lang=ALL-UNNAMED"))
}
