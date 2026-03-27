package ro.gs1s.mvnresfilter

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

/**
 * Cache is stored in [cacheDir] (typically project's target/ or build/ directory),
 * NOT inside the artifact output directory. File is named per artifact to support
 * multiple artifacts from the same project.
 */
class OverlayCache(private val cacheDir: Path, artifactName: String) {

    private val safeName = artifactName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    private val cacheFile: Path = cacheDir.resolve(".overlay-cache-$safeName")

    fun isUpToDate(inputs: OverlayCacheInputs): Boolean {
        if (!Files.exists(cacheFile)) return false
        return try {
            val props = Properties()
            Files.newBufferedReader(cacheFile).use { props.load(it) }
            val storedHash = props.getProperty("hash") ?: return false
            if (storedHash != computeHash(inputs)) return false

            // Verify that all output files still exist with the same content hash
            val outputFileHashes = props.getProperty("outputFileHashes") ?: return false
            if (outputFileHashes.isBlank()) return false
            outputFileHashes.split("\n").all { entry ->
                val (path, expectedHash) = entry.split("=", limit = 2)
                val file = Path.of(path)
                Files.exists(file) && hashFile(file) == expectedHash
            }
        } catch (e: Exception) {
            false
        }
    }

    fun writeCache(inputs: OverlayCacheInputs, outputFiles: List<Path>) {
        Files.createDirectories(cacheDir)
        val props = Properties()
        props.setProperty("hash", computeHash(inputs))
        props.setProperty("timestamp", Instant.now().toString())
        props.setProperty("profiles", inputs.profiles.joinToString(","))
        props.setProperty("outputFileHashes", outputFiles.joinToString("\n") { "${it}=${hashFile(it)}" })
        Files.newBufferedWriter(cacheFile).use { props.store(it, "Maven Resource Overlay cache") }
    }

    private fun hashFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
