# Maven Resource Overlay

IntelliJ IDEA plugin that automatically applies Maven resource filtering and `<webResources>` overlays from active profiles when building exploded WAR or JAR artifacts.

Partial fix for the long-standing IntelliJ limitation [IDEA-25934](https://youtrack.jetbrains.com/issue/IDEA-25934) where the artifact builder ignores `maven-war-plugin` configuration from Maven profiles.

## Features

- Copies profile-specific `<webResources>` into the artifact output, overlaying files per the active Maven profile
- Applies `${...}` property substitution in resources configured with `<filtering>true</filtering>`
- Loads properties from external filter files (`<build><filters>`) at both project and profile level, with correct Maven priority order
- Filters deployment descriptors when `<filteringDeploymentDescriptors>` is enabled
- Respects `<nonFilteredFileExtensions>` for binary files
- Respects `<includes>` and `<excludes>` patterns
- Property substitution in resource paths (`<directory>`, `<include>`, `<exclude>`, `<targetPath>`, `<filter>`)
- 30+ built-in Maven properties (`project.groupId`, `project.build.directory`, `basedir`, system properties, etc.)
- Caches results with content hashing — skips reprocessing when nothing changed
- Shows processing details in the Build tool window

## Supported Project Types

- **WAR** — exploded WAR artifacts with `<webResources>` and resource filtering
- **JAR** — JAR artifacts with `<resources><filtering>true</filtering>`

## Usage

1. Activate a Maven profile in the Maven tool window
2. Build your exploded artifact (Build → Build Artifacts)
3. The plugin automatically post-processes the artifact output
4. Check the Build tool window for details

## Requirements

- IntelliJ IDEA Ultimate 2025.2+
- Maven project with `maven-war-plugin` or resource filtering configuration

## Limitations

- Environment variables (`${env.VAR}`) and `settings.xml` properties are not supported
- Only `${property}` syntax is supported. The `@property@` delimiter style (Spring Boot) is not supported
- Multi-module projects: each module must be built as a separate artifact. Parent property inheritance works only when both POMs are in the project

## Building from Source

```bash
./gradlew build          # Full build (compile + test + verify)
./gradlew test           # Run tests
./gradlew runIde         # Launch IntelliJ sandbox with plugin installed
./gradlew buildPlugin    # Build distributable plugin ZIP
```

## License

Copyright (c) Cristinel Grigoras. All rights reserved.
