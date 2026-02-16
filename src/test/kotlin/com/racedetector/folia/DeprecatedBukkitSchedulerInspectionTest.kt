package com.racedetector.folia

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class DeprecatedBukkitSchedulerInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(DeprecatedBukkitSchedulerInspection())
        addBukkitMocks()
    }

    /**
     * Test 1: Bukkit.getScheduler().runTask() → ERROR in Folia project.
     */
    fun testRunTaskDetected() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;

            public class Test {
                public void test(Plugin plugin) {
                    Bukkit.getScheduler().runTask(plugin, () -> {});
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("BukkitScheduler.runTask()", HighlightSeverity.ERROR)
    }

    /**
     * Test 2: Bukkit.getScheduler().runTaskAsynchronously() → ERROR.
     */
    fun testRunTaskAsyncDetected() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;

            public class Test {
                public void test(Plugin plugin) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {});
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("BukkitScheduler.runTaskAsynchronously()", HighlightSeverity.ERROR)
    }

    /**
     * Test 3: Bukkit.getScheduler().scheduleSyncDelayedTask() → ERROR.
     */
    fun testScheduleSyncDelayedTaskDetected() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;

            public class Test {
                public void test(Plugin plugin) {
                    Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {});
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("BukkitScheduler.scheduleSyncDelayedTask()", HighlightSeverity.ERROR)
    }

    /**
     * Test 4: Folia-compatible alternatives do NOT produce warnings.
     */
    fun testFoliaCompatibleAlternativesNoWarning() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;

            public class Test {
                public void test(Plugin plugin) {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> {});
                    Bukkit.getAsyncScheduler().runNow(plugin, task -> {});
                }
            }
            """.trimIndent()
        )
        assertNoSchedulerHighlights()
    }

    /**
     * Test 5: Non-Folia project → BukkitScheduler calls produce no warnings.
     */
    fun testNonFoliaProjectNoWarning() {
        // Don't call enableFoliaDetection() — simulates a non-Folia (Bukkit/Spigot) project
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;

            public class Test {
                public void test(Plugin plugin) {
                    Bukkit.getScheduler().runTask(plugin, () -> {});
                }
            }
            """.trimIndent()
        )
        assertNoSchedulerHighlights()
    }

    // ==================== Helpers ====================

    private fun assertHasHighlight(messageFragment: String, expectedSeverity: HighlightSeverity) {
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains(messageFragment) == true }
        assertFalse(
            "Expected highlight containing '$messageFragment', but found none. " +
                "All highlights: ${allHighlightsDescription()}",
            highlights.isEmpty()
        )
        val matchingSeverity = highlights.filter { it.severity == expectedSeverity }
        assertFalse(
            "Expected severity $expectedSeverity for '$messageFragment', " +
                "but got: ${highlights.joinToString { "${it.severity}: '${it.description}'" }}",
            matchingSeverity.isEmpty()
        )
    }

    private fun assertNoSchedulerHighlights() {
        val highlights = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WARNING.myVal }
            .filter { it.description?.contains("BukkitScheduler") == true }
        assertTrue(
            "Expected no BukkitScheduler warnings, but found: " +
                highlights.joinToString { "'${it.description}'" },
            highlights.isEmpty()
        )
    }

    private fun allHighlightsDescription(): String {
        val all = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WARNING.myVal }
        if (all.isEmpty()) return "none"
        return all.joinToString { "${it.severity}: '${it.description}'" }
    }

    // ==================== Mock classes ====================

    private fun enableFoliaDetection() {
        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions;
            public class RegionizedServer {}
            """.trimIndent()
        )
    }

    private fun addBukkitMocks() {
        myFixture.addClass(
            """
            package java.util.function;
            public interface Consumer<T> {
                void accept(T t);
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.plugin;
            public interface Plugin {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.scheduler;
            import org.bukkit.plugin.Plugin;
            public interface BukkitScheduler {
                void runTask(Plugin plugin, Runnable task);
                void runTaskLater(Plugin plugin, Runnable task, long delay);
                void runTaskTimer(Plugin plugin, Runnable task, long delay, long period);
                void runTaskAsynchronously(Plugin plugin, Runnable task);
                void runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay);
                void runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period);
                int scheduleSyncDelayedTask(Plugin plugin, Runnable task);
                int scheduleSyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period);
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions.scheduler;
            import org.bukkit.plugin.Plugin;
            import java.util.function.Consumer;
            public interface GlobalRegionScheduler {
                void run(Plugin plugin, Consumer task);
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions.scheduler;
            import org.bukkit.plugin.Plugin;
            import java.util.function.Consumer;
            public interface AsyncScheduler {
                void runNow(Plugin plugin, Consumer task);
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit;
            import org.bukkit.scheduler.BukkitScheduler;
            import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
            import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
            public class Bukkit {
                public static BukkitScheduler getScheduler() { return null; }
                public static GlobalRegionScheduler getGlobalRegionScheduler() { return null; }
                public static AsyncScheduler getAsyncScheduler() { return null; }
            }
            """.trimIndent()
        )
    }
}
