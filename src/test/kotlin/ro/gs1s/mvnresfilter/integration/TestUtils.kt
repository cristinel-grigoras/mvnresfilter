package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import ro.gs1s.mvnresfilter.OverlayCacheInputs
import ro.gs1s.mvnresfilter.OverlayConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object TestUtils {
    val PROJECTS_BASE: Path = Path.of("src/test/resources/projects")
    val EXPECTED_BASE: Path = Path.of("src/test/resources/expected")

    fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dest = target.resolve(source.relativize(src))
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    fun readText(path: Path): String =
        Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n")

    fun compareTree(expectedDir: Path, actualDir: Path) {
        Files.walk(expectedDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { expectedFile ->
                val relative = expectedDir.relativize(expectedFile)
                val actualFile = actualDir.resolve(relative)
                assertTrue("Expected file missing in output: $relative", Files.exists(actualFile))
                assertEquals(
                    "Content mismatch for: $relative",
                    readText(expectedFile),
                    readText(actualFile)
                )
            }
        }
    }

    fun resolveRelativePaths(config: OverlayConfig): OverlayConfig {
        val basedir = config.projectBasedir
        val resolvedWebResources = config.webResources.map { wr ->
            val dir = Path.of(wr.directory)
            val absDir = if (dir.isAbsolute) dir else basedir.resolve(dir)
            wr.copy(directory = absDir.toString())
        }
        val resolvedResources = config.resources.map { r ->
            val dir = Path.of(r.directory)
            val absDir = if (dir.isAbsolute) dir else basedir.resolve(dir)
            r.copy(directory = absDir.toString())
        }
        return config.copy(
            webResources = resolvedWebResources,
            resources = resolvedResources
        )
    }

    fun buildCacheInputs(config: OverlayConfig): OverlayCacheInputs {
        val sourceFiles = mutableMapOf<String, Long>()
        for (resource in config.resources) {
            val dir = Path.of(resource.directory)
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        sourceFiles[file.toString()] = Files.getLastModifiedTime(file).toMillis()
                    }
                }
            }
        }
        for (webResource in config.webResources) {
            val dir = Path.of(webResource.directory)
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        sourceFiles[file.toString()] = Files.getLastModifiedTime(file).toMillis()
                    }
                }
            }
        }
        return OverlayCacheInputs(
            profiles = config.activeProfiles,
            properties = config.mergedProperties,
            sourceFiles = sourceFiles
        )
    }
}
