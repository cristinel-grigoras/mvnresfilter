package ro.gs1s.mvnresfilter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class OverlayCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun makeInputs(
        profiles: List<String> = emptyList(),
        properties: Map<String, String> = emptyMap(),
        sourceFiles: Map<String, Long> = emptyMap()
    ) = OverlayCacheInputs(profiles = profiles, properties = properties, sourceFiles = sourceFiles)

    // -------------------------------------------------------------------------
    // Test 1: no .overlay-cache file → isUpToDate() returns false
    // -------------------------------------------------------------------------
    @Test
    fun testFirstRun_NoFlagFile_Processes() {
        val outputDir = tempFolder.newFolder("output").toPath()
        val cache = OverlayCache(outputDir, "test-artifact")
        val inputs = makeInputs(profiles = listOf("dev"))

        assertFalse(cache.isUpToDate(inputs))
    }

    // -------------------------------------------------------------------------
    // Test 2: writeCache() then isUpToDate() with same inputs → true
    // -------------------------------------------------------------------------
    @Test
    fun testFlagFileExists_HashMatches_Skips() {
        val outputDir = tempFolder.newFolder("output").toPath()
        val cache = OverlayCache(outputDir, "test-artifact")
        val inputs = makeInputs(
            profiles = listOf("dev"),
            properties = mapOf("db.url" to "jdbc:h2:mem:test"),
            sourceFiles = mapOf("src/main/resources/app.properties" to 1234567890L)
        )

        cache.writeCache(inputs)

        assertTrue(cache.isUpToDate(inputs))
    }

    // -------------------------------------------------------------------------
    // Test 3: writeCache() with profiles=["dev"], isUpToDate() with profiles=["prod"] → false
    // -------------------------------------------------------------------------
    @Test
    fun testFlagFileExists_HashChanged_Reprocesses() {
        val outputDir = tempFolder.newFolder("output").toPath()
        val cache = OverlayCache(outputDir, "test-artifact")
        val devInputs = makeInputs(profiles = listOf("dev"))
        val prodInputs = makeInputs(profiles = listOf("prod"))

        cache.writeCache(devInputs)

        assertFalse(cache.isUpToDate(prodInputs))
    }

    // -------------------------------------------------------------------------
    // Test 4: write garbage to .overlay-cache, isUpToDate() → false
    // -------------------------------------------------------------------------
    @Test
    fun testFlagFileCorrupt_Reprocesses() {
        val outputDir = tempFolder.newFolder("output").toPath()
        val cache = OverlayCache(outputDir, "test-artifact")
        val inputs = makeInputs(profiles = listOf("dev"))

        // Write garbage content that cannot be parsed as a valid Properties file with a "hash" key
        Files.writeString(outputDir.resolve(".overlay-cache-test-artifact"), "\u0000\u0001\u0002GARBAGE_NO_HASH_KEY")

        assertFalse(cache.isUpToDate(inputs))
    }

    // -------------------------------------------------------------------------
    // Test 5: writeCache(), create expected output, delete it, isUpToDate() → false
    // -------------------------------------------------------------------------
    @Test
    fun testOutputDeleted_Reprocesses() {
        val outputDir = tempFolder.newFolder("output").toPath()
        val cache = OverlayCache(outputDir, "test-artifact")
        val inputs = makeInputs(profiles = listOf("dev"))

        cache.writeCache(inputs)

        val expectedOutput = outputDir.resolve("app.properties")
        Files.writeString(expectedOutput, "key=value")

        // Verify it's up to date when the file exists
        assertTrue(cache.isUpToDate(inputs, listOf(expectedOutput)))

        // Delete the expected output file
        Files.delete(expectedOutput)

        // Now it should not be up to date
        assertFalse(cache.isUpToDate(inputs, listOf(expectedOutput)))
    }

    // -------------------------------------------------------------------------
    // Test 6: writeCache() creates .overlay-cache file that is readable
    // -------------------------------------------------------------------------
    @Test
    fun testSuccessfulProcessing_WritesCacheFile() {
        val outputDir = tempFolder.newFolder("output").toPath()
        val cache = OverlayCache(outputDir, "test-artifact")
        val inputs = makeInputs(
            profiles = listOf("prod"),
            properties = mapOf("app.name" to "MyApp"),
            sourceFiles = mapOf("src/main/resources/config.xml" to 9876543210L)
        )

        cache.writeCache(inputs)

        val cacheFile = outputDir.resolve(".overlay-cache-test-artifact")
        assertTrue(".overlay-cache file should exist after writeCache()", Files.exists(cacheFile))
        assertTrue(".overlay-cache file should be readable", Files.isReadable(cacheFile))
        val content = Files.readString(cacheFile)
        assertTrue("Cache file should contain a hash entry", content.contains("hash="))
    }
}
