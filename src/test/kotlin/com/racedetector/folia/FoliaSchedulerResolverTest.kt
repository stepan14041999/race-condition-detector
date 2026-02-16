package com.racedetector.folia

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.racedetector.analysis.ThreadContext
import com.racedetector.threading.ThreadContextResolver

class FoliaSchedulerResolverTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        addFoliaSchedulerApiMocks()
    }

    /**
     * Adds mock classes for Folia scheduler API (without the marker class).
     * Call [enableFoliaDetection] in individual tests that need isFoliaProject() == true.
     */
    private fun addFoliaSchedulerApiMocks() {
        // Consumer functional interface (not in light JDK)
        myFixture.addClass(
            """
            package java.util.function;
            @FunctionalInterface
            public interface Consumer<T> {
                void accept(T t);
            }
            """.trimIndent()
        )

        // Bukkit Plugin
        myFixture.addClass(
            """
            package org.bukkit.plugin;
            public interface Plugin {}
            """.trimIndent()
        )

        // Bukkit Location
        myFixture.addClass(
            """
            package org.bukkit;
            public class Location {}
            """.trimIndent()
        )

        // ScheduledTask
        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions.scheduler;
            public interface ScheduledTask {}
            """.trimIndent()
        )

        // GlobalRegionScheduler
        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions.scheduler;
            import java.util.function.Consumer;
            import org.bukkit.plugin.Plugin;
            public interface GlobalRegionScheduler {
                void run(Plugin plugin, Consumer<ScheduledTask> task);
                void runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delayTicks);
                void runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks);
            }
            """.trimIndent()
        )

        // RegionScheduler
        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions.scheduler;
            import java.util.function.Consumer;
            import org.bukkit.plugin.Plugin;
            import org.bukkit.Location;
            public interface RegionScheduler {
                void run(Plugin plugin, Location location, Consumer<ScheduledTask> task);
                void runDelayed(Plugin plugin, Location location, Consumer<ScheduledTask> task, long delayTicks);
            }
            """.trimIndent()
        )

        // AsyncScheduler
        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions.scheduler;
            import java.util.function.Consumer;
            import org.bukkit.plugin.Plugin;
            public interface AsyncScheduler {
                void runNow(Plugin plugin, Consumer<ScheduledTask> task);
                void runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delay, long unit);
                void runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task, long initialDelay, long period, long unit);
            }
            """.trimIndent()
        )

        // EntityScheduler
        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions.scheduler;
            import java.util.function.Consumer;
            import org.bukkit.plugin.Plugin;
            public interface EntityScheduler {
                void run(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired);
                void runDelayed(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired, long delayTicks);
            }
            """.trimIndent()
        )

        // Bukkit main class
        myFixture.addClass(
            """
            package org.bukkit;
            import io.papermc.paper.threadedregions.scheduler.*;
            public class Bukkit {
                public static GlobalRegionScheduler getGlobalRegionScheduler() { return null; }
                public static RegionScheduler getRegionScheduler() { return null; }
                public static AsyncScheduler getAsyncScheduler() { return null; }
            }
            """.trimIndent()
        )

        // Entity
        myFixture.addClass(
            """
            package org.bukkit.entity;
            import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
            public interface Entity {
                EntityScheduler getScheduler();
            }
            """.trimIndent()
        )
    }

    private fun enableFoliaDetection() {
        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions;
            public class RegionizedServer {}
            """.trimIndent()
        )
    }

    // 1. Lambda in GlobalRegionScheduler.run() → FoliaGlobalThread
    fun testGlobalRegionSchedulerRun() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin) {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                        int x = <caret>42;
                    });
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.FoliaGlobalThread)
    }

    // 2. Lambda in RegionScheduler.run() → FoliaRegionThread
    fun testRegionSchedulerRun() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.Location;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin, Location loc) {
                    Bukkit.getRegionScheduler().run(plugin, loc, task -> {
                        int x = <caret>42;
                    });
                }
            }
            """.trimIndent()
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val context = ThreadContextResolver.resolve(element)
        assertTrue(
            "Expected FoliaRegionThread but got $context",
            context is ThreadContext.FoliaRegionThread
        )
    }

    // 3. Lambda in AsyncScheduler.runNow() → FoliaAsyncThread
    fun testAsyncSchedulerRunNow() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin) {
                    Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                        int x = <caret>42;
                    });
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.FoliaAsyncThread)
    }

    // 4. Lambda in entity.getScheduler().run() → FoliaEntityThread
    fun testEntitySchedulerRun() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.entity.Entity;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin, Entity entity) {
                    entity.getScheduler().run(plugin, task -> {
                        int x = <caret>42;
                    }, null);
                }
            }
            """.trimIndent()
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val context = ThreadContextResolver.resolve(element)
        assertTrue(
            "Expected FoliaEntityThread but got $context",
            context is ThreadContext.FoliaEntityThread
        )
    }

    // 5. Lambda in GlobalRegionScheduler.runAtFixedRate() → FoliaGlobalThread
    fun testGlobalRegionSchedulerRunAtFixedRate() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin) {
                    Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                        int x = <caret>42;
                    }, 1L, 20L);
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.FoliaGlobalThread)
    }

    // 6. Code outside Folia scheduler → normal ThreadContext (Unknown)
    fun testCodeOutsideFoliaScheduler() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                void test() {
                    int x = <caret>42;
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.Unknown)
    }

    // 7. Nested lambda (rescheduling) → inner context wins
    fun testNestedLambdaInnerContextWins() {
        enableFoliaDetection()
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin) {
                    Bukkit.getGlobalRegionScheduler().run(plugin, outerTask -> {
                        Bukkit.getAsyncScheduler().runNow(plugin, innerTask -> {
                            int x = <caret>42;
                        });
                    });
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.FoliaAsyncThread)
    }

    // 8. Non-Folia project → Folia scheduler detection not applied
    fun testNonFoliaProjectIgnoresSchedulers() {
        // No enableFoliaDetection() — RegionizedServer is absent
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin) {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                        int x = <caret>42;
                    });
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.Unknown)
    }

    private fun assertContext(expected: ThreadContext) {
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val actual = ThreadContextResolver.resolve(element)
        assertEquals(expected, actual)
    }
}
