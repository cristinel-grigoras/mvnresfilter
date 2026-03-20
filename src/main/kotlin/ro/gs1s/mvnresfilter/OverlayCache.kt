package ro.gs1s.mvnresfilter

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

class OverlayCache(private val outputDir: Path) {
    private val cacheFile: Path = outputDir.resolve(".overlay-cache")

    fun isUpToDate(inputs: OverlayCacheInputs, expectedOutputPaths: List<Path> = emptyList()): Boolean {
        if (!Files.exists(cacheFile)) return false
        if (expectedOutputPaths.any { !Files.exists(it) }) return false
        return try {
            val props = Properties()
            Files.newBufferedReader(cacheFile).use { props.load(it) }
            val storedHash = props.getProperty("hash") ?: return false
            storedHash == computeHash(inputs)
        } catch (e: Exception) {
            false
        }
    }

    fun writeCache(inputs: OverlayCacheInputs) {
        Files.createDirectories(outputDir)
        val props = Properties()
        props.setProperty("hash", computeHash(inputs))
        props.setProperty("timestamp", Instant.now().toString())
        props.setProperty("profiles", inputs.profiles.joinToString(","))
        Files.newBufferedWriter(cacheFile).use { props.store(it, "Maven Resource Overlay cache") }
    }

    private fun computeHash(inputs: OverlayCacheInputs): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputs.profiles.sorted().forEach { digest.update(it.toByteArray()) }
        inputs.properties.toSortedMap().forEach { (k, v) ->
            digest.update(k.toByteArray())
            digest.update(v.toByteArray())
        }
        inputs.sourceFiles.toSortedMap().forEach { (path, lastMod) ->
            digest.update(path.toByteArray())
            digest.update(lastMod.toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
