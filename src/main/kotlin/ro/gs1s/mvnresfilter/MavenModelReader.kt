package ro.gs1s.mvnresfilter

import org.apache.maven.model.Model
import org.apache.maven.model.Profile
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.io.FileReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and parses Maven pom.xml files to extract profile names, properties,
 * resources, and maven-war-plugin configuration.
 *
 * Property merge order: project-level props → active-profile props (pom declaration order,
 * later wins) → built-in properties (project.*, pom.*, basedir, groupId, etc.).
 *
 * activeByDefault semantics: if any explicit profile ID is activated, all
 * activeByDefault profiles are excluded from the effective set.
 */
class MavenModelReader {

    private val xpp3Reader = MavenXpp3Reader()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns all profile IDs declared in the given pom.xml. */
    fun parseProfileNames(pomPath: Path): List<String> {
        val model = readModel(pomPath)
        return model.profiles.map { it.id }
    }

    /**
     * Builds an [OverlayConfig] from a single pom.xml (no parent).
     */
    fun buildConfig(
        pomPath: Path,
        activeProfileIds: List<String>,
        artifactOutputPath: Path,
        artifactType: ArtifactType
    ): OverlayConfig {
        val model = readModel(pomPath)
        return buildConfigFromModel(
            childModel = model,
            parentModel = null,
            pomPath = pomPath,
            activeProfileIds = activeProfileIds,
            artifactOutputPath = artifactOutputPath,
            artifactType = artifactType
        )
    }

    /**
     * Builds an [OverlayConfig] from a child pom.xml and its parent pom.xml.
     * Parent properties are applied first; child / profile properties override them.
     */
    fun buildConfigWithParent(
        childPomPath: Path,
        parentPomPath: Path,
        activeProfileIds: List<String>,
        artifactOutputPath: Path,
        artifactType: ArtifactType
    ): OverlayConfig {
        val childModel = readModel(childPomPath)
        val parentModel = readModel(parentPomPath)
        return buildConfigFromModel(
            childModel = childModel,
            parentModel = parentModel,
            pomPath = childPomPath,
            activeProfileIds = activeProfileIds,
            artifactOutputPath = artifactOutputPath,
            artifactType = artifactType
        )
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun readModel(pomPath: Path): Model {
        FileReader(pomPath.toFile()).use { reader ->
            return xpp3Reader.read(reader)
        }
    }

    private fun buildConfigFromModel(
        childModel: Model,
        parentModel: Model?,
        pomPath: Path,
        activeProfileIds: List<String>,
        artifactOutputPath: Path,
        artifactType: ArtifactType
    ): OverlayConfig {
        val projectBasedir = pomPath.parent ?: pomPath.toAbsolutePath().parent

        // Determine effective active profiles (considers activeByDefault)
        val allProfiles: List<Profile> = buildCombinedProfileList(parentModel, childModel)
        val effectiveProfileIds = resolveEffectiveProfileIds(allProfiles, activeProfileIds)

        // Merge properties: parent project → child project → active profile properties
        val mergedProps = mergeProperties(
            childModel = childModel,
            parentModel = parentModel,
            effectiveProfileIds = effectiveProfileIds,
            allProfiles = allProfiles,
            projectBasedir = projectBasedir
        )

        // Collect resources and resolve ${...} in directory/targetPath before downstream use
        val resources = collectResources(childModel).map { r ->
            r.copy(
                directory = resolvePropertyPlaceholders(r.directory, mergedProps),
                targetPath = r.targetPath?.let { resolvePropertyPlaceholders(it, mergedProps) },
                includes = r.includes.map { resolvePropertyPlaceholders(it, mergedProps) },
                excludes = r.excludes.map { resolvePropertyPlaceholders(it, mergedProps) }
            )
        }

        // Collect war-plugin config and resolve ${...} in webResource directories
        val warPluginData = collectWarPluginData(childModel, allProfiles, effectiveProfileIds)
        val webResources = warPluginData.webResources.map { w ->
            w.copy(
                directory = resolvePropertyPlaceholders(w.directory, mergedProps),
                includes = w.includes.map { resolvePropertyPlaceholders(it, mergedProps) },
                excludes = w.excludes.map { resolvePropertyPlaceholders(it, mergedProps) }
            )
        }

        return OverlayConfig(
            artifactType = artifactType,
            activeProfiles = effectiveProfileIds,
            mergedProperties = mergedProps,
            webResources = webResources,
            resources = resources,
            nonFilteredExtensions = warPluginData.nonFilteredExtensions,
            filterDeploymentDescriptors = warPluginData.filterDeploymentDescriptors,
            projectBasedir = projectBasedir,
            artifactOutputPath = artifactOutputPath
        )
    }

    /**
     * Returns profiles from parent (if any) followed by child profiles.
     * For profile resolution purposes we only look at child profiles
     * (the parent's activeByDefault and explicit activation are separate concerns),
     * but we include parent profiles so that their properties can be merged.
     */
    private fun buildCombinedProfileList(parentModel: Model?, childModel: Model): List<Profile> {
        val combined = mutableListOf<Profile>()
        parentModel?.profiles?.let { combined.addAll(it) }
        combined.addAll(childModel.profiles)
        return combined
    }

    /**
     * Resolves which profile IDs are effectively active.
     *
     * Rules:
     * - If [requestedIds] is non-empty, activate exactly those profiles;
     *   any profile that is only activeByDefault is NOT included.
     * - If [requestedIds] is empty, activate profiles that have
     *   `activeByDefault = true`.
     */
    private fun resolveEffectiveProfileIds(
        allProfiles: List<Profile>,
        requestedIds: List<String>
    ): List<String> {
        if (requestedIds.isNotEmpty()) {
            // Explicit activation: keep only profiles whose id is in the requested list.
            // activeByDefault profiles are NOT included (Maven semantics).
            return requestedIds.filter { id -> allProfiles.any { it.id == id } }
        }
        // No explicit activation: use activeByDefault
        return allProfiles
            .filter { it.activation?.isActiveByDefault == true }
            .map { it.id }
    }

    /**
     * Merges properties matching Maven's actual priority order (lowest → highest):
     * 0. basedir defined early (needed for resolving filter file paths)
     * 1. Project-level filter files (<build><filters>)
     * 2. Active profile filter files (profile <build><filters>)
     * 3. Parent model project-level <properties>
     * 4. Child model project-level <properties>
     * 5. Active profile <properties> (in pom declaration order, later wins)
     * 6. Built-in properties (project.*, pom.*, short aliases)
     *
     * After all properties are set, a single pass resolves ${...} within property values.
     */
    private fun mergeProperties(
        childModel: Model,
        parentModel: Model?,
        effectiveProfileIds: List<String>,
        allProfiles: List<Profile>,
        projectBasedir: Path
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val basedirStr = projectBasedir.toAbsolutePath().toString()

        // 0. Define basedir early so filter file paths can use ${basedir}
        result["basedir"] = basedirStr
        result["project.basedir"] = basedirStr
        result["pom.basedir"] = basedirStr

        // 1. Project-level filter files
        childModel.build?.filters?.forEach { filterPath ->
            val resolved = resolvePropertyPlaceholders(filterPath, result)
            loadFilterFile(projectBasedir, resolved, result)
        }

        // 2. Active profile filter files
        for (profile in allProfiles) {
            if (profile.id in effectiveProfileIds) {
                profile.build?.filters?.forEach { filterPath ->
                    val resolved = resolvePropertyPlaceholders(filterPath, result)
                    loadFilterFile(projectBasedir, resolved, result)
                }
            }
        }

        // 3. Parent project properties
        parentModel?.properties?.forEach { k, v -> result[k.toString()] = v.toString() }

        // 4. Child project properties
        childModel.properties?.forEach { k, v -> result[k.toString()] = v.toString() }

        // 5. Active profile properties (pom declaration order → later wins)
        for (profile in allProfiles) {
            if (profile.id in effectiveProfileIds) {
                profile.properties?.forEach { k, v -> result[k.toString()] = v.toString() }
            }
        }

        // 6. Built-in properties (highest priority — cannot be overridden)
        val artifactId = childModel.artifactId ?: parentModel?.artifactId ?: ""
        val groupId = childModel.groupId ?: childModel.parent?.groupId ?: parentModel?.groupId ?: ""
        val version = childModel.version ?: childModel.parent?.version ?: parentModel?.version ?: ""
        val name = childModel.name ?: artifactId
        val packaging = childModel.packaging ?: "jar"

        // Build directories: use model values if set, otherwise Maven defaults
        val buildDir = childModel.build?.directory ?: "$basedirStr/target"
        val sourceDir = childModel.build?.sourceDirectory ?: "$basedirStr/src/main/java"
        val outputDir = childModel.build?.outputDirectory ?: "$basedirStr/target/classes"
        val testSourceDir = childModel.build?.testSourceDirectory ?: "$basedirStr/src/test/java"
        val testOutputDir = childModel.build?.testOutputDirectory ?: "$basedirStr/target/test-classes"
        val finalName = childModel.build?.finalName ?: "$artifactId-$version"

        // project.* properties
        result["project.groupId"] = groupId
        result["project.artifactId"] = artifactId
        result["project.version"] = version
        result["project.name"] = name
        result["project.packaging"] = packaging
        result["project.basedir"] = basedirStr
        result["project.build.directory"] = buildDir
        result["project.build.sourceDirectory"] = sourceDir
        result["project.build.outputDirectory"] = outputDir
        result["project.build.testSourceDirectory"] = testSourceDir
        result["project.build.testOutputDirectory"] = testOutputDir
        result["project.build.finalName"] = finalName

        // Short aliases
        result["groupId"] = groupId
        result["artifactId"] = artifactId
        result["version"] = version
        result["basedir"] = basedirStr

        // pom.* aliases (deprecated but still widely used)
        result["pom.groupId"] = groupId
        result["pom.artifactId"] = artifactId
        result["pom.version"] = version
        result["pom.basedir"] = basedirStr

        // Java system properties commonly used in Maven poms
        result["user.dir"] = System.getProperty("user.dir", "")
        result["user.home"] = System.getProperty("user.home", "")
        result["user.name"] = System.getProperty("user.name", "")
        result["java.home"] = System.getProperty("java.home", "")
        result["java.version"] = System.getProperty("java.version", "")
        result["os.name"] = System.getProperty("os.name", "")
        result["os.arch"] = System.getProperty("os.arch", "")
        result["file.separator"] = System.getProperty("file.separator", "/")
        result["path.separator"] = System.getProperty("path.separator", ":")
        result["line.separator"] = System.getProperty("line.separator", "\n")

        // Resolve ${...} within property values themselves (single pass)
        for ((key, value) in result.toMap()) {
            if (value.contains("\${")) {
                result[key] = resolvePropertyPlaceholders(value, result)
            }
        }

        return result
    }

    /**
     * Loads properties from a filter file. Path is resolved relative to projectBasedir.
     */
    private fun loadFilterFile(projectBasedir: Path, filterPath: String, target: MutableMap<String, String>) {
        val file = projectBasedir.resolve(filterPath)
        if (!Files.exists(file)) return
        try {
            val props = java.util.Properties()
            Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8).use { props.load(it) }
            props.forEach { k, v -> target[k.toString()] = v.toString() }
        } catch (e: Exception) {
            // Skip unreadable filter files
        }
    }

    /**
     * Collects standard <resources> from the child model's project-level build section.
     */
    private fun collectResources(model: Model): List<ResourceDef> {
        val build = model.build ?: return emptyList()
        return build.resources.map { r ->
            ResourceDef(
                directory = r.directory ?: "src/main/resources",
                includes = r.includes ?: emptyList(),
                excludes = r.excludes ?: emptyList(),
                filtering = r.isFiltering,
                targetPath = r.targetPath
            )
        }
    }

    // -------------------------------------------------------------------------
    // War-plugin config parsing
    // -------------------------------------------------------------------------

    private data class WarPluginData(
        val webResources: List<WebResourceDef>,
        val nonFilteredExtensions: Set<String>,
        val filterDeploymentDescriptors: Boolean
    )

    /**
     * Iterates active profiles in pom declaration order and accumulates
     * maven-war-plugin configuration.
     */
    private fun collectWarPluginData(
        model: Model,
        allProfiles: List<Profile>,
        effectiveProfileIds: List<String>
    ): WarPluginData {
        val webResources = mutableListOf<WebResourceDef>()
        val extensions = mutableSetOf<String>()
        var filterDescriptors = false

        // Check if any active profile has its own war-plugin config
        var profileHasWarPlugin = false
        for (profile in allProfiles) {
            if (profile.id !in effectiveProfileIds) continue
            if (findWarPlugin(profile) != null) {
                profileHasWarPlugin = true
                break
            }
        }

        if (profileHasWarPlugin) {
            // Profile war-plugin replaces project-level (Maven behavior)
            for (profile in allProfiles) {
                if (profile.id !in effectiveProfileIds) continue
                val warPlugin = findWarPlugin(profile) ?: continue
                val config = warPlugin.configuration as? Xpp3Dom ?: continue
                parseWarPluginConfig(config, webResources, extensions) { filterDescriptors = true }
            }
        } else {
            // No active profile has war-plugin — use project-level
            val projectWarPlugin = model.build?.plugins?.firstOrNull {
                it.artifactId == "maven-war-plugin"
            }
            if (projectWarPlugin != null) {
                val config = projectWarPlugin.configuration as? Xpp3Dom
                if (config != null) {
                    parseWarPluginConfig(config, webResources, extensions) { filterDescriptors = true }
                }
            }
        }

        return WarPluginData(webResources, extensions, filterDescriptors)
    }

    private fun parseWarPluginConfig(
        config: Xpp3Dom,
        webResources: MutableList<WebResourceDef>,
        extensions: MutableSet<String>,
        onFilterDescriptors: () -> Unit
    ) {
        config.getChild("filteringDeploymentDescriptors")?.value?.let {
            if (it.equals("true", ignoreCase = true)) onFilterDescriptors()
        }

        config.getChild("nonFilteredFileExtensions")?.children?.forEach { child ->
            val ext = child.value?.trim()
            if (!ext.isNullOrEmpty()) extensions.add(ext)
        }

        config.getChild("webResources")?.children?.forEach { resourceDom ->
            webResources.add(parseDomWebResource(resourceDom))
        }
    }

    private fun findWarPlugin(profile: Profile): org.apache.maven.model.Plugin? {
        val build = profile.build ?: return null
        return build.plugins?.firstOrNull { plugin ->
            plugin.artifactId == "maven-war-plugin"
        }
    }

    companion object {
        private val propertyPattern = Regex("""\$\{([^}]+)}""")

        /** Substitutes `${...}` placeholders in [value] using [properties]. Unresolved keys are left as-is. */
        internal fun resolvePropertyPlaceholders(value: String, properties: Map<String, String>): String {
            if (!value.contains("\${")) return value
            return propertyPattern.replace(value) { match ->
                properties[match.groupValues[1]] ?: match.value
            }
        }
    }

    private fun parseDomWebResource(dom: Xpp3Dom): WebResourceDef {
        val directory = dom.getChild("directory")?.value ?: ""
        val filtering = dom.getChild("filtering")?.value?.equals("true", ignoreCase = true) ?: false

        val includes = dom.getChild("includes")?.children
            ?.mapNotNull { it.value?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val excludes = dom.getChild("excludes")?.children
            ?.mapNotNull { it.value?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        return WebResourceDef(
            directory = directory,
            includes = includes,
            excludes = excludes,
            filtering = filtering
        )
    }
}
