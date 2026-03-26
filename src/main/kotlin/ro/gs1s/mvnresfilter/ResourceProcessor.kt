package ro.gs1s.mvnresfilter

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

class ResourceProcessor @JvmOverloads constructor(
    private val config: OverlayConfig,
    private val overlayLog: OverlayLog? = null
) {

    companion object {
        /** Switch to parallel processing when file count exceeds this threshold */
        const val PARALLEL_THRESHOLD = 50
    }

    private val log = Logger.getInstance(ResourceProcessor::class.java)
    private val propertyPattern = Regex("""\$\{([^}]+)}""")
    private val fileCountAtomic = AtomicInteger(0)
    private val errorsConcurrent = ConcurrentLinkedQueue<String>()
    private val outputFilesConcurrent = ConcurrentLinkedQueue<Path>()

    fun getErrors(): List<String> = errorsConcurrent.toList()
    fun getFileCount(): Int = fileCountAtomic.get()
    fun getOutputFiles(): List<Path> = outputFilesConcurrent.toList()

    fun processResources() {
        for (resource in config.resources) {
            val sourceDir = config.projectBasedir.resolve(resource.directory)
            log.debug("processResources: basedir=${config.projectBasedir}, dir=${resource.directory}, resolved=$sourceDir, exists=${Files.exists(sourceDir)}")
            if (!Files.exists(sourceDir) || !sourceDir.isDirectory()) {
                log.warn("Resource source directory does not exist, skipping: $sourceDir")
                overlayLog?.skipped("Resource dir not found: ${resource.directory}")
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
            val sourceDir = config.projectBasedir.resolve(webResource.directory)
            if (!Files.exists(sourceDir) || !sourceDir.isDirectory()) {
                log.warn("Web resource source directory does not exist, skipping: ${webResource.directory}")
                overlayLog?.skipped("WebResource dir not found: ${webResource.directory}")
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
                val replacements = filterFileInPlace(descriptorFile)
                log.debug("Filtered deployment descriptor: $descriptorName ($replacements properties replaced)")
                overlayLog?.filtered("WEB-INF/$descriptorName", replacements)
                fileCountAtomic.incrementAndGet()
                outputFilesConcurrent.add(descriptorFile)
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
        // Collect matching files first
        val filesToProcess = mutableListOf<Path>()
        Files.walk(sourceDir).use { stream ->
            stream.filter { it.isRegularFile() }.forEach { sourceFile ->
                val relativePath = sourceFile.relativeTo(sourceDir).toString()
                if (matchesPatterns(relativePath, includes, excludes)) {
                    filesToProcess.add(sourceFile)
                }
            }
        }

        if (filesToProcess.size > PARALLEL_THRESHOLD) {
            log.debug("Processing ${filesToProcess.size} files in parallel (threshold=$PARALLEL_THRESHOLD)")
            overlayLog?.let {
                // Synchronize log access for parallel — messages may interleave but that's acceptable
            }
            filesToProcess.parallelStream().forEach { sourceFile ->
                processFile(sourceFile, sourceDir, targetDir, filter)
            }
        } else {
            for (sourceFile in filesToProcess) {
                processFile(sourceFile, sourceDir, targetDir, filter)
            }
        }
    }

    private fun processFile(sourceFile: Path, sourceDir: Path, targetDir: Path, filter: Boolean) {
        val relativePath = sourceFile.relativeTo(sourceDir).toString()
        try {
            val targetFile = targetDir.resolve(relativePath)
            Files.createDirectories(targetFile.parent)

            val ext = sourceFile.extension.lowercase()
            if (filter && ext !in config.nonFilteredExtensions) {
                try {
                    val content = Files.readString(sourceFile, StandardCharsets.UTF_8)
                    val filtered = filterContent(content)
                    val replacements = countReplacements(content, filtered)
                    Files.writeString(targetFile, filtered, StandardCharsets.UTF_8)
                    log.debug("Filtered: $relativePath ($replacements properties replaced)")
                    overlayLog?.filtered(relativePath, replacements)
                } catch (e: MalformedInputException) {
                    log.warn("File is not valid UTF-8, copying as binary: $sourceFile")
                    Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
                    overlayLog?.copied("$relativePath (binary fallback)")
                }
            } else {
                Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
                log.debug("Copied: $relativePath")
                overlayLog?.copied(relativePath)
            }
            fileCountAtomic.incrementAndGet()
            outputFilesConcurrent.add(targetFile)
        } catch (e: Exception) {
            val msg = "Failed to process $relativePath: ${e.message}"
            log.warn(msg, e)
            overlayLog?.skipped(msg)
            errorsConcurrent.add(msg)
        }
    }

    /**
     * Filters a file in-place. Returns the number of properties replaced.
     */
    private fun filterFileInPlace(file: Path): Int {
        try {
            val content = Files.readString(file, StandardCharsets.UTF_8)
            val filtered = filterContent(content)
            val replacements = countReplacements(content, filtered)
            Files.writeString(file, filtered, StandardCharsets.UTF_8)
            return replacements
        } catch (e: MalformedInputException) {
            log.warn("File is not valid UTF-8, skipping filter in-place: $file")
            return 0
        }
    }

    private fun filterContent(content: String): String {
        return propertyPattern.replace(content) { matchResult ->
            val key = matchResult.groupValues[1]
            val value = config.mergedProperties[key]
            if (value == null) {
                log.debug("Property not found: $key, leaving as-is")
                matchResult.value
            } else {
                value
            }
        }
    }

    private fun countReplacements(original: String, filtered: String): Int {
        val originalMatches = propertyPattern.findAll(original).count()
        val filteredMatches = propertyPattern.findAll(filtered).count()
        return originalMatches - filteredMatches
    }

    private fun matchesPatterns(path: String, includes: List<String>, excludes: List<String>): Boolean {
        val fs = FileSystems.getDefault()
        val normalizedPath = path.replace('\\', '/')

        for (exclude in excludes) {
            if (globMatches(fs, exclude, normalizedPath)) return false
        }

        if (includes.isEmpty()) return true

        for (include in includes) {
            if (globMatches(fs, include, normalizedPath)) return true
        }

        return false
    }

    private fun globMatches(fs: java.nio.file.FileSystem, pattern: String, path: String): Boolean {
        val matcher = fs.getPathMatcher("glob:$pattern")
        if (matcher.matches(Path.of(path))) return true
        val fileName = Path.of(path).fileName?.toString() ?: return false
        if (fileName != path) return false
        val trimmedPattern = pattern.removePrefix("**/")
        if (trimmedPattern != pattern) {
            val trimmedMatcher = fs.getPathMatcher("glob:$trimmedPattern")
            if (trimmedMatcher.matches(Path.of(fileName))) return true
        }
        return false
    }
}
