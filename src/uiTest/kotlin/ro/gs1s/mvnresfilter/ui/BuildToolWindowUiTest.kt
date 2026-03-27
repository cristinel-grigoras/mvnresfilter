package ro.gs1s.mvnresfilter.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * UI test for verifying the Build tool window output after Maven Resource Overlay runs.
 *
 * Usage:
 *   Terminal 1: Xvfb :99 -screen 0 1920x1080x24 &
 *               UI_TEST_DISPLAY=:99 ./gradlew runIdeForUiTests
 *   Terminal 2: ./gradlew uiTest
 *
 * Or use the all-in-one script: bash build/run-ui-test.sh
 */
@Suppress(
    "JSUnresolvedReference", "JSUnresolvedVariable", "JSUnresolvedFunction",
    "JSDuplicatedDeclaration", "JSVoidFunctionReturnValueUsed",
    "ES6ConvertVarToLetConst", "JSUnusedLocalSymbols", "JSValidateTypes"
)
class BuildToolWindowUiTest {

    private lateinit var robot: RemoteRobot
    private lateinit var projectDir: Path
    private var stepCounter = 0

    companion object {
        private const val ROBOT_PORT = 8082
        private val MINIMAL_WAR_SOURCE = File("src/test/resources/projects/minimal-war").absoluteFile.toPath()
        private val SCREENSHOT_DIR = File("build/reports/ui-screenshots").also { it.mkdirs() }
    }

    @Before
    fun setUp() {
        Assume.assumeTrue(
            "Robot Server not running on port $ROBOT_PORT. Start IDE first: ./gradlew runIdeForUiTests",
            isPortOpen(ROBOT_PORT)
        )
        robot = RemoteRobot(
            "http://127.0.0.1:$ROBOT_PORT",
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        )

        waitForIdeReady()
        bringIdeToFront()
        handleDialogs(3)

        projectDir = Files.createTempDirectory("mvnresfilter-uitest-")
        MINIMAL_WAR_SOURCE.toFile().copyRecursively(projectDir.toFile())
    }

    @After
    fun tearDown() {
        if (::robot.isInitialized) {
            screenshot("final")
        }
        if (::projectDir.isInitialized) {
            projectDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testBuildToolWindowShowsOverlayMessages() {
        screenshot("01-before-open")
        openProject()

        screenshot("02-after-open")
        waitForImport()
        expandProjectTree()

        screenshot("03-project-tree")
        activateDevProfile()

        screenshot("04-after-profile")
        buildArtifacts()

        screenshot("05-after-build")
        openBuildToolWindow()

        screenshot("06-build-tool-window")
        selectOverlayBuilder()
        screenshot("07-overlay-output")
        assertOverlayRan()
    }

    // --- Screenshot ---

    private fun screenshot(label: String) {
        runCatching {
            val bytes = robot.callJs<ByteArray>("""
                importPackage(java.awt)
                importPackage(java.awt.image)
                importPackage(java.io)
                importPackage(javax.imageio)
                var frames = Frame.getFrames();
                var frame = null;
                for (var i = 0; i < frames.length; i++) {
                    if (frames[i].isVisible()) { frame = frames[i]; break; }
                }
                if (frame == null) throw new java.lang.RuntimeException("No visible frame");
                var img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
                var g = img.createGraphics();
                frame.paint(g);
                g.dispose();
                var baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                baos.toByteArray();
            """, runInEdt = true)

            val image: BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))
            val file = File(SCREENSHOT_DIR, "${String.format("%02d", stepCounter++)}-$label.png")
            ImageIO.write(image, "png", file)
            println("Screenshot: ${file.name}")
        }.onFailure { println("Screenshot failed ($label): ${it.message}") }
    }

    // --- Helpers ---

    private fun selectPopupItem(textContains: String) {
        runCatching {
            val popup = robot.find<ComponentFixture>(
                byXpath("//div[@class='MyList']"), Duration.ofSeconds(3)
            )
            popup.callJs<Boolean>("""
                var list = component;
                var model = list.getModel();
                for (var i = 0; i < model.getSize(); i++) {
                    var item = model.getElementAt(i).toString();
                    if (item.indexOf("$textContains") >= 0) {
                        list.setSelectedIndex(i);
                        break;
                    }
                }
                true;
            """, runInEdt = true)
            robot.keyboard { enter() }
        }
    }

    private fun isPortOpen(port: Int): Boolean {
        return runCatching { Socket("127.0.0.1", port).use { true } }.getOrDefault(false)
    }

    private fun requireRobotAlive() {
        check(isPortOpen(ROBOT_PORT)) { "Robot Server port $ROBOT_PORT is no longer open — IDE crashed?" }
    }

    private fun waitForIdeReady() {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                robot.callJs<Boolean>("""true;""", runInEdt = true)
            }.getOrDefault(false)
            if (ok) return
            Thread.sleep(2000)
        }
        throw AssertionError("IDE did not become ready within 60s")
    }

    private fun bringIdeToFront() {
        robot.callJs<Boolean>("""
            var frames = java.awt.Frame.getFrames();
            for (var i = 0; i < frames.length; i++) {
                if (frames[i].isVisible()) {
                    frames[i].toFront();
                    frames[i].requestFocus();
                    break;
                }
            }
            true;
        """, runInEdt = true)
    }

    private fun handleDialogs(seconds: Int) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val clicked = robot.callJs<String>("""
                var result = "";
                var windows = java.awt.Window.getWindows();
                for (var w = 0; w < windows.length; w++) {
                    if (!(windows[w] instanceof java.awt.Dialog) || !windows[w].isVisible()) continue;
                    var queue = new java.util.LinkedList();
                    queue.add(windows[w]);
                    while (queue.size() > 0) {
                        var comp = queue.poll();
                        try {
                            var text = comp.getText();
                            if (text != null) {
                                var targets = ["This Window", "Trust Project"];
                                for (var t = 0; t < targets.length; t++) {
                                    if (text.equals(targets[t])) {
                                        comp.doClick();
                                        result = text;
                                    }
                                }
                            }
                        } catch(e) {}
                        if (result.length > 0) break;
                        try {
                            for (var c = 0; c < comp.getComponentCount(); c++) {
                                queue.add(comp.getComponent(c));
                            }
                        } catch(e) {}
                    }
                    if (result.length > 0) break;
                }
                result;
            """, runInEdt = true)
            if (clicked.isNotEmpty()) {
                println("Clicked dialog button: $clicked")
                Thread.sleep(500)
            } else {
                break
            }
        }
    }

    // --- Project lifecycle ---

    private fun openProject() {
        requireRobotAlive()

        val projectPath = projectDir.toAbsolutePath().toString()

        // loadAndOpenProject runs in a background thread — it blocks too long for EDT.
        // openOrImport is a suspend function in 2025.2 and cannot be called from Rhino.
        robot.callJs<String>("""
            new java.lang.Thread(new java.lang.Runnable({ run: function() {
                try {
                    var pm = com.intellij.openapi.project.ProjectManager.getInstance();
                    pm.loadAndOpenProject("$projectPath");
                } catch(e) {
                    java.lang.System.err.println("loadAndOpenProject error: " + e);
                }
            }})).start();
            "started";
        """, runInEdt = true)

        // Poll until a project is open
        for (@Suppress("unused") i in 1..30) {
            handleDialogs(1)
            val count = robot.callJs<String>("""
                "" + com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length;
            """, runInEdt = true)
            if (count != "0") break
            Thread.sleep(2000)
        }

        requireRobotAlive()
        robot.find<ComponentFixture>(byXpath("//div[@class='IdeFrameImpl']"), Duration.ofSeconds(30))
    }

    private fun waitForImport() {
        robot.find<ComponentFixture>(
            byXpath("//div[contains(@accessiblename, 'Maven') and @class='SquareStripeButton']"),
            Duration.ofSeconds(30)
        )
        Thread.sleep(5000)
    }

    private fun expandProjectTree() {
        val treeInfo = try {
            robot.callJs<String>("""
                // Open Project tool window and find its tree
                var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                var wm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                var projectTw = wm.getToolWindow("Project");
                if (projectTw != null) projectTw.show();

                function findTree(comp) {
                    var cls = comp.getClass().getName();
                    if (cls.indexOf("Tree") >= 0 && comp.getRowCount && comp.expandRow) {
                        try { if (comp.getRowCount() > 0) return comp; } catch(e) {}
                    }
                    try {
                        for (var i = 0; i < comp.getComponentCount(); i++) {
                            var found = findTree(comp.getComponent(i));
                            if (found != null) return found;
                        }
                    } catch(e) {}
                    return null;
                }
                var tree = projectTw != null ? findTree(projectTw.getComponent()) : null;
                if (tree != null) {
                    tree.expandRow(0);
                    var row = 1;
                    while (row < tree.getRowCount() && row < 40) {
                        var node = tree.getPathForRow(row).getLastPathComponent().toString();
                        if (node.indexOf("External") >= 0 || node.indexOf("Scratches") >= 0
                                || node.indexOf("loading") >= 0) {
                            tree.collapseRow(row);
                            row++;
                            continue;
                        }
                        tree.expandRow(row);
                        row++;
                    }
                    // Scroll to top so project root is visible
                    tree.scrollRowToVisible(0);
                    "expanded, rows=" + tree.getRowCount();
                } else {
                    "no tree";
                }
            """, runInEdt = true)
        } catch (e: Exception) { "error: ${e.message}" }
        println("expandProjectTree pass 1: $treeInfo")
        // Wait for lazy nodes to load, then expand again
        Thread.sleep(5000)
        val treeInfo2 = try {
            robot.callJs<String>("""
                var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                var wm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                var projectTw = wm.getToolWindow("Project");
                function findTree(comp) {
                    var cls = comp.getClass().getName();
                    if (cls.indexOf("Tree") >= 0 && comp.getRowCount && comp.expandRow) {
                        try { if (comp.getRowCount() > 0) return comp; } catch(e) {}
                    }
                    try {
                        for (var i = 0; i < comp.getComponentCount(); i++) {
                            var found = findTree(comp.getComponent(i));
                            if (found != null) return found;
                        }
                    } catch(e) {}
                    return null;
                }
                var tree = projectTw != null ? findTree(projectTw.getComponent()) : null;
                if (tree != null) {
                    var row = 0;
                    while (row < tree.getRowCount() && row < 40) {
                        var node = tree.getPathForRow(row).getLastPathComponent().toString();
                        if (node.indexOf("External") >= 0 || node.indexOf("Scratches") >= 0) {
                            tree.collapseRow(row);
                            row++;
                            continue;
                        }
                        tree.expandRow(row);
                        row++;
                    }
                    tree.scrollRowToVisible(0);
                    "expanded, rows=" + tree.getRowCount();
                } else { "no tree"; }
            """, runInEdt = true)
        } catch (e: Exception) { "error: ${e.message}" }
        println("expandProjectTree pass 2: $treeInfo2")
        Thread.sleep(2000)
        // Third pass to catch any remaining lazy nodes
        try {
            robot.callJs<String>("""
                var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                var wm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                var tw = wm.getToolWindow("Project");
                function findTree(comp) {
                    var cls = comp.getClass().getName();
                    if (cls.indexOf("Tree") >= 0 && comp.getRowCount && comp.expandRow) {
                        try { if (comp.getRowCount() > 0) return comp; } catch(e) {}
                    }
                    try {
                        for (var i = 0; i < comp.getComponentCount(); i++) {
                            var f = findTree(comp.getComponent(i));
                            if (f != null) return f;
                        }
                    } catch(e) {}
                    return null;
                }
                var tree = tw != null ? findTree(tw.getComponent()) : null;
                if (tree != null) {
                    var row = 0;
                    while (row < tree.getRowCount() && row < 50) {
                        var node = tree.getPathForRow(row).getLastPathComponent().toString();
                        if (node.indexOf("External") >= 0 || node.indexOf("Scratches") >= 0) {
                            tree.collapseRow(row); row++; continue;
                        }
                        tree.expandRow(row); row++;
                    }
                    tree.scrollRowToVisible(0);
                }
                "done";
            """, runInEdt = true)
        } catch (_: Exception) {}
    }

    private fun activateDevProfile() {
        robot.callJs<Boolean>("""
            var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            var pluginId = com.intellij.openapi.extensions.PluginId.findId("org.jetbrains.idea.maven");
            var cl = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId).getPluginClassLoader();
            var MpmClass = java.lang.Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager", true, cl);
            var ProjectClass = java.lang.Class.forName("com.intellij.openapi.project.Project");
            var mpm = MpmClass.getMethod("getInstance", ProjectClass).invoke(null, project);
            var profiles = mpm.getExplicitProfiles();
            var enabled = new java.util.LinkedHashSet(profiles.getEnabledProfiles());
            enabled.add("dev");
            var MepClass = java.lang.Class.forName("org.jetbrains.idea.maven.model.MavenExplicitProfiles", true, cl);
            var newProfiles = MepClass.getConstructors()[0].newInstance(enabled, profiles.getDisabledProfiles());
            mpm.setExplicitProfiles(newProfiles);
            true;
        """, runInEdt = true)

        Thread.sleep(5000)
    }

    private fun buildArtifacts() {
        robot.callJs<Boolean>("""
            var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            var actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance();
            var action = actionManager.getAction("BuildArtifact");
            var dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project);
            var event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                "BuildArtifact", null, dataContext
            );
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                new java.lang.Runnable({ run: function() {
                    action.actionPerformed(event);
                }})
            );
            true;
        """, runInEdt = true)

        Thread.sleep(1000)
        selectPopupItem("exploded")

        Thread.sleep(500)
        selectPopupItem("Build")

        Thread.sleep(5000)
    }

    // --- Build tool window verification ---

    private fun openBuildToolWindow() {
        runCatching {
            robot.find<ComponentFixture>(
                byXpath("//div[contains(@accessiblename, 'Build') and @class='SquareStripeButton']"),
                Duration.ofSeconds(3)
            ).click()
        }
        robot.find<ComponentFixture>(byXpath("//div[@class='BuildView']"), Duration.ofSeconds(5))
    }

    /** Select the "Maven Resource Overlay" entry in the Build tool window's left panel. */
    private fun selectOverlayBuilder() {
        val result = runCatching {
            robot.callJs<String>("""
                var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                var wm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                var tw = wm.getToolWindow("Build");
                var info = "no Build tw";
                if (tw != null) {
                    var cm = tw.getContentManager();
                    // The Build Output content has a MultipleBuildsView with multiple BuildView tabs.
                    // Access via the SyncViewManager service.
                    var svm = project.getService(
                        java.lang.Class.forName("com.intellij.build.BuildViewManager")
                    );
                    // Get all BuildProgress descriptors and find ours
                    // Alternative: iterate ContentTabLabel children in the content component
                    var content = cm.getSelectedContent();
                    var component = content.getComponent();
                    // Dump all ContentTabLabel/TabLabel children to find ours
                    // Build execution entries are in a JBList in the left panel
                    function findJBList(comp) {
                        if (comp.getClass().getSimpleName().equals("JBList")) return comp;
                        try {
                            for (var i = 0; i < comp.getComponentCount(); i++) {
                                var found = findJBList(comp.getComponent(i));
                                if (found != null) return found;
                            }
                        } catch(e) {}
                        return null;
                    }
                    var list = findJBList(tw.getComponent());
                    if (list != null) {
                        var model = list.getModel();
                        var items = [];
                        for (var i = 0; i < model.getSize(); i++) {
                            var elem = model.getElementAt(i);
                            // BuildInfo has getTitle() method
                            var title = "";
                            try { title = elem.getTitle(); } catch(e) {}
                            if (!title) try { title = elem.toString(); } catch(e2) {}
                            items.push(i + ":" + title);
                            if (title.indexOf("Maven") >= 0 || title.indexOf("Overlay") >= 0) {
                                list.setSelectedIndex(i);
                                info = "selected " + i + ": " + title;
                            }
                        }
                        if (info.indexOf("selected") < 0) {
                            // Fallback: select last item (overlay runs after standard build)
                            if (model.getSize() > 1) {
                                list.setSelectedIndex(model.getSize() - 1);
                                info = "fallback selected last: " + items.join(", ");
                            } else {
                                info = "items=" + items.join(", ");
                            }
                        }
                    } else {
                        info = "no JBList found";
                    }
                }
                info;
            """, runInEdt = true)
        }.getOrElse { "error: ${it.message}" }
        println("selectOverlayBuilder: $result")
        Thread.sleep(500)
    }

    private fun assertOverlayRan() {
        // Collect accessible names from all visible components to find overlay output.
        val visibleText = robot.callJs<String>("""
            var sb = new java.lang.StringBuilder();
            function collectText(comp) {
                try {
                    var ctx = comp.getAccessibleContext();
                    if (ctx != null) {
                        var name = ctx.getAccessibleName();
                        var desc = ctx.getAccessibleDescription();
                        if (name != null && name.length() > 0) sb.append(name).append("\\n");
                        if (desc != null && desc.length() > 0) sb.append(desc).append("\\n");
                    }
                } catch(e) {}
                try {
                    for (var i = 0; i < comp.getComponentCount(); i++) {
                        collectText(comp.getComponent(i));
                    }
                } catch(e) {}
            }
            var frames = java.awt.Frame.getFrames();
            for (var f = 0; f < frames.length; f++) {
                if (frames[f].isVisible()) collectText(frames[f]);
            }
            sb.toString();
        """, runInEdt = true)

        assert(visibleText.contains("Maven Resource Overlay")) {
            "Build view should contain 'Maven Resource Overlay'.\nVisible text:\n${
                visibleText.split("\\n").filter { it.isNotBlank() }.joinToString("\n")
            }"
        }
    }
}
