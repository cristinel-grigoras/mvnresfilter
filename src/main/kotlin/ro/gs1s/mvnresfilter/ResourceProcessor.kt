package ro.gs1s.mvnresfilter

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

class ResourceProcessor(private val config: OverlayConfig) {

    private val log = Logger.getInstance(ResourceProcessor::class.java)
    private val propertyPattern = Regex("""\$\{([^}]+)}""")

    fun processResources() {
        for (resource in config.resources) {
            val sourceDir = Path.of(resource.directory)
            if (!Files.exists(sourceDir) || !sourceDir.isDirectory()) {
                log.warn("Resource source directory does not exist, skipping: ${resource.directory}")
                continue
            }
            val targetDir = if (resource.targetPath != null) {
                config.resourceOutputDir.resolve(resource.targetPath)
            } else {
                config.resourceOutputDir
            }
            processDirectory(
                sourceDir = sourceDir,
                targetDir = targetDir,
                includes = resource.includes,
                excludes = resource.excludes,
                filter = resource.filtering
            )
        }
    }

    fun processWebResources() {
        for (webResource in config.webResources) {
            val sourceDir = Path.of(webResource.directory)
            if (!Files.exists(sourceDir) || !sourceDir.isDirectory()) {
                log.warn("Web resource source directory does not exist, skipping: ${webResource.directory}")
                continue
            }
            processDirectory(
                sourceDir = sourceDir,
                targetDir = config.artifactOutputPath,
                includes = webResource.includes,
                excludes = webResource.excludes,
                filter = webResource.filtering
            )
        }
    }

    fun filterDeploymentDescriptors() {
        if (!config.filterDeploymentDescriptors) return
        val webInfDir = config.artifactOutputPath.resolve("WEB-INF")
        for (descriptorName in listOf("web.xml", "jboss-web.xml")) {
            val descriptorFile = webInfDir.resolve(descriptorName)
            if (Files.exists(descriptorFile) && descriptorFile.isRegularFile()) {
                filterFileInPlace(descriptorFile)
            }
        }
    }

    private fun processDirectory(
        sourceDir: Path,
        targetDir: Path,
        includes: List<String>,
        excludes: List<String>,
        filter: Boolean
    ) {
        Files.walk(sourceDir).use { stream ->
            stream.filter { it.isRegularFile() }.forEach { sourceFile ->
                val relativePath = sourceFile.relativeTo(sourceDir).toString()
                if (!matchesPatterns(relativePath, includes, excludes)) return@forEach

                val targetFile = targetDir.resolve(relativePath)
                Files.createDirectories(targetFile.parent)

                val ext = sourceFile.extension.lowercase()
                if (filter && ext !in config.nonFilteredExtensions) {
                    try {
                        val content = Files.readString(sourceFile, StandardCharsets.UTF_8)
                        val filtered = filterContent(content)
                        Files.writeString(targetFile, filtered, StandardCharsets.UTF_8)
                    } catch (e: MalformedInputException) {
                        log.warn("File is not valid UTF-8, copying as binary: $sourceFile")
                        Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                } else {
                    Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun filterFileInPlace(file: Path) {
        try {
            val content = Files.readString(file, StandardCharsets.UTF_8)
            val filtered = filterContent(content)
            Files.writeString(file, filtered, StandardCharsets.UTF_8)
        } catch (e: MalformedInputException) {
            log.warn("File is not valid UTF-8, skipping filter in-place: $file")
        }
    }

    private fun filterContent(content: String): String {
        return propertyPattern.replace(content) { matchResult ->
            val key = matchResult.groupValues[1]
            val value = config.mergedProperties[key]
            if (value == null) {
                log.warn("Property not found: $key, leaving as-is")
                matchResult.value
            } else {
                value
            }
        }
    }

    private fun matchesPatterns(path: String, includes: List<String>, excludes: List<String>): Boolean {
        val fs = FileSystems.getDefault()

        // Normalize separators to forward slash for matching
        val normalizedPath = path.replace('\\', '/')

        // Check excludes — if any exclude matches, skip the file
        for (exclude in excludes) {
            if (globMatches(fs, exclude, normalizedPath)) return false
        }

        // If no includes specified, all files are included
        if (includes.isEmpty()) return true

        // Check includes — file must match at least one include
        for (include in includes) {
            if (globMatches(fs, include, normalizedPath)) return true
        }

        return false
    }

    /**
     * Matches a glob pattern against a relative path.
     * NIO's PathMatcher with "glob:**" does not match paths without a separator at the top level,
     * so we also try matching the filename alone for patterns that start with "**".
     */
    private fun globMatches(fs: java.nio.file.FileSystem, pattern: String, path: String): Boolean {
        val matcher = fs.getPathMatcher("glob:$pattern")
        if (matcher.matches(Path.of(path))) return true
        // For patterns like **/*.ext, also try matching just the filename so root-level files are covered
        val fileName = Path.of(path).fileName?.toString() ?: return false
        if (fileName != path) return false // already tried a multi-segment path above
        // path has no separator — try matching the single filename against the pattern without leading **/
        val trimmedPattern = pattern.removePrefix("**/")
        if (trimmedPattern != pattern) {
            val trimmedMatcher = fs.getPathMatcher("glob:$trimmedPattern")
            if (trimmedMatcher.matches(Path.of(fileName))) return true
        }
        return false
    }
}
