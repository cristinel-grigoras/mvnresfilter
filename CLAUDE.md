# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Maven Resource Overlay** — an IntelliJ IDEA plugin that fixes IntelliJ's inability to process Maven's `maven-war-plugin` `<webResources>` configuration from active profiles (IDEA-25934). It handles profile-based file overlays (e.g., `keycloak.json`, `oidc.json`) and `${...}` property substitution in files like `jboss-web.xml` and `persistence.xml` during artifact builds.

Plugin ID: `ro.gs1s.mvnresfilter.mvnresfilter`
Package: `ro.gs1s.mvnresfilter`

## Build Commands

```bash
./gradlew build              # Full build (compile + test + verify)
./gradlew test               # Run tests
./gradlew runIde             # Launch IntelliJ sandbox with plugin installed
./gradlew verifyPlugin       # Check plugin compatibility against target IDEs
./gradlew buildPlugin        # Build distributable plugin ZIP
```

## Tech Stack & Targets

- **Language:** Kotlin (JVM 21)
- **Build:** Gradle 9.0 with Kotlin DSL, IntelliJ Platform Gradle Plugin 2.10.2
- **Target IDE:** IntelliJ IDEA 2025.2+ (sinceBuild `252.25557`)
- **Dependencies:** `com.intellij.java`, `org.jetbrains.kotlin` bundled plugins; planned: `org.jetbrains.idea.maven` bundled plugin and `maven-filtering` library

## Architecture

The plugin hooks into IntelliJ's artifact build system via `ArtifactBuildTaskProvider`. When IntelliJ builds an exploded WAR, the plugin post-processes it by:

1. Reading Maven project model (active profiles, properties, war plugin config) via IntelliJ's Maven integration API
2. Copying `<webResources>` files (profile-specific overlays) to the artifact output
3. Applying `${...}` property substitution using the `maven-filtering` library for Maven-accurate filtering
4. Filtering deployment descriptors if `<filteringDeploymentDescriptors>` is enabled

Key planned components:
- `MavenResourceOverlayTaskProvider` — registers the build task for WAR artifacts
- `MavenResourceOverlayTask` — orchestrates the overlay pipeline
- `MavenModelReader` — extracts profiles, properties, webResources, resources config from pom.xml
- `ResourceProcessor` — handles file copying and `${...}` substitution
- `OverlayConfig` — data classes for parsed configuration

The design plan is in `plans/2026-03-20-maven-resource-overlay-design.md`.

## Current State

The project is scaffolded from the JetBrains plugin template. The existing `MyToolWindow*` classes are template placeholders to be replaced. The actual plugin implementation (the architecture above) has not been built yet.

## Key Conventions

- Source lives under `src/main/kotlin/ro/gs1s/mvnresfilter/`
- Plugin manifest: `src/main/resources/META-INF/plugin.xml`
- Message bundle: `src/main/resources/messages/MyMessageBundle.properties`
- Extension point registrations go in `plugin.xml` under `<extensions defaultExtensionNs="com.intellij">`
- Plugin dependencies declared as `<depends>` in `plugin.xml` and `bundledPlugin()` in `build.gradle.kts`
