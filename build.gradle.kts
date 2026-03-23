plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ro.gs1.idea"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Maven)

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("com.intellij.javaee")
        bundledPlugin("com.intellij.javaee.web")
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        // Change notes are in plugin.xml <change-notes>
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Platform integration tests (src/test/kotlin/**/platform/**) require the full IntelliJ
// test environment (JBR, sandbox, instrumented classes). The IntelliJ Platform Gradle Plugin
// only configures the built-in 'test' task with these settings, so we filter via includes/excludes
// rather than creating a separate Test task.
tasks.test { exclude("**/platform/**") }

// Extract test task configuration values eagerly to avoid configuration cache serialization issues.
// The jvmArgumentProviders from the IntelliJ Platform plugin contain objects that hold task references,
// so we resolve them eagerly into a flat list of strings.
val testTask = tasks.test.get()
val testTaskClassesDirs = testTask.testClassesDirs
val testTaskClasspath = testTask.classpath
val testTaskJavaLauncher = testTask.javaLauncher
val testTaskSystemProperties = testTask.systemProperties.toMap()
val testTaskJvmArgs = testTask.allJvmArgs.toList()

tasks.register<Test>("integrationTest") {
    description = "Runs platform integration tests requiring the IntelliJ test environment"
    group = "verification"

    dependsOn(tasks.named("prepareTest"))

    // Mirror the test task's full configuration (classpath, JVM, sandbox, system properties)
    testClassesDirs = testTaskClassesDirs
    classpath = testTaskClasspath
    javaLauncher = testTaskJavaLauncher
    systemProperties(testTaskSystemProperties)
    jvmArgs(testTaskJvmArgs)

    // Disable rethrow of logged errors — the IDE's internal Fleet/Rete engine logs
    // NoSuchMethodError from Kotlin stdlib version mismatch, which is not our code.
    // Without this, MavenImportingTestCase skips tests on any logged error.
    systemProperty("intellij.testFramework.rethrow.logged.errors", "false")

    // Only run tests under the platform package
    include("**/platform/**")

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
