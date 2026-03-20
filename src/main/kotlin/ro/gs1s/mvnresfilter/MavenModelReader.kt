package ro.gs1s.mvnresfilter

import org.apache.maven.model.Model
import org.apache.maven.model.Profile
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.io.FileReader
import java.nio.file.Path

/**
 * Reads and parses Maven pom.xml files to extract profile names, properties,
 * resources, and maven-war-plugin configuration.
 *
 * Property merge order: project-level props → active-profile props (pom declaration order,
 * later wins) → built-in properties (project.artifactId, project.version,
 * project.basedir, basedir).
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

        // Collect resources from child model's project build (profile build resources are separate)
        val resources = collectResources(childModel)

        // Collect war-plugin config from project build + active profiles
        val warPluginData = collectWarPluginData(childModel, allProfiles, effectiveProfileIds)

        return OverlayConfig(
            artifactType = artifactType,
            activeProfiles = effectiveProfileIds,
            mergedProperties = mergedProps,
            webResources = warPluginData.webResources,
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
     * Merges properties in the following priority order (lowest → highest):
     * 1. Parent model project-level properties
     * 2. Child model project-level properties
     * 3. Active profile properties (in profile declaration order within the combined list)
     * 4. Built-in properties (project.artifactId, project.version, project.basedir, basedir)
     */
    private fun mergeProperties(
        childModel: Model,
        parentModel: Model?,
        effectiveProfileIds: List<String>,
        allProfiles: List<Profile>,
        projectBasedir: Path
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // 1. Parent project properties
        parentModel?.properties?.forEach { k, v -> result[k.toString()] = v.toString() }

        // 2. Child project properties
        childModel.properties?.forEach { k, v -> result[k.toString()] = v.toString() }

        // 3. Active profile properties (pom declaration order → later wins)
        for (profile in allProfiles) {
            if (profile.id in effectiveProfileIds) {
                profile.properties?.forEach { k, v -> result[k.toString()] = v.toString() }
            }
        }

        // 4. Built-in properties
        val artifactId = childModel.artifactId ?: parentModel?.artifactId ?: ""
        val version = childModel.version ?: childModel.parent?.version ?: parentModel?.version ?: ""
        val basedirStr = projectBasedir.toAbsolutePath().toString()
        result["project.artifactId"] = artifactId
        result["project.version"] = version
        result["project.basedir"] = basedirStr
        result["basedir"] = basedirStr

        return result
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

        // 1. Project-level war-plugin config (default build section)
        val projectWarPlugin = model.build?.plugins?.firstOrNull {
            it.artifactId == "maven-war-plugin"
        }
        if (projectWarPlugin != null) {
            val config = projectWarPlugin.configuration as? Xpp3Dom
            if (config != null) {
                parseWarPluginConfig(config, webResources, extensions) { filterDescriptors = true }
            }
        }

        // 2. Active profile war-plugin config (overrides/extends project-level)
        for (profile in allProfiles) {
            if (profile.id !in effectiveProfileIds) continue
            val warPlugin = findWarPlugin(profile) ?: continue
            val config = warPlugin.configuration as? Xpp3Dom ?: continue
            parseWarPluginConfig(config, webResources, extensions) { filterDescriptors = true }
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
