package com.racedetector.folia

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FoliaCrossContextInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(FoliaCrossContextInspection())
        addFoliaMocks()
        enableFoliaDetection()
    }

    // ==================== FR-6.1: Entity access from wrong thread ====================

    // 1. player.getHealth() from GlobalRegionScheduler → ERROR
    fun testEntityAccessFromGlobalScheduler() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;
            import org.bukkit.entity.Player;
            public class Test {
                void test(Plugin plugin, Player player) {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                        player.getHealth();
                    });
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("Accessing Player API from global scheduler thread", HighlightSeverity.ERROR)
    }

    // 2. entity.setCustomName() from AsyncScheduler → ERROR
    fun testEntityAccessFromAsyncScheduler() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.plugin.Plugin;
            import org.bukkit.entity.Entity;
            public class Test {
                void test(Plugin plugin, Entity entity) {
                    Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                        entity.setCustomName("test");
                    });
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("Accessing Entity API from async thread", HighlightSeverity.ERROR)
    }

    // 3. player.getHealth() from player.getScheduler().run() → OK (same entity)
    fun testEntityAccessFromSameEntityScheduler() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.entity.Player;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin, Player player) {
                    player.getScheduler().run(plugin, task -> {
                        player.getHealth();
                    }, null);
                }
            }
            """.trimIndent()
        )
        assertNoFoliaHighlights()
    }

    // 4. otherPlayer.getHealth() from player.getScheduler().run() → ERROR (different entity)
    fun testEntityAccessFromDifferentEntityScheduler() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.entity.Player;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin, Player player, Player otherPlayer) {
                    player.getScheduler().run(plugin, task -> {
                        otherPlayer.getHealth();
                    }, null);
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("Accessing Player API from another entity's scheduler thread", HighlightSeverity.ERROR)
    }

    // 5. player.getHealth() from RegionScheduler → WARNING
    fun testEntityAccessFromRegionScheduler() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.Location;
            import org.bukkit.plugin.Plugin;
            import org.bukkit.entity.Player;
            public class Test {
                void test(Plugin plugin, Location loc, Player player) {
                    Bukkit.getRegionScheduler().run(plugin, loc, task -> {
                        player.getHealth();
                    });
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("Accessing Player API from region scheduler", HighlightSeverity.WARNING)
    }

    // ==================== FR-6.2: Block/Chunk access from wrong thread ====================

    // 6. block.setType() from entity scheduler → WARNING
    fun testBlockAccessFromEntityScheduler() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.entity.Player;
            import org.bukkit.block.Block;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin, Player player, Block block) {
                    player.getScheduler().run(plugin, task -> {
                        block.setType(null);
                    }, null);
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("Accessing Block API from entity scheduler", HighlightSeverity.WARNING)
    }

    // 7. chunk.getEntities() from GlobalRegionScheduler → ERROR
    fun testChunkAccessFromGlobalScheduler() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.Chunk;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin, Chunk chunk) {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                        chunk.getEntities();
                    });
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("Accessing Chunk API from global scheduler thread", HighlightSeverity.ERROR)
    }

    // 8. block.setType() from RegionScheduler → OK (correct region context)
    fun testBlockAccessFromRegionScheduler() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.Bukkit;
            import org.bukkit.Location;
            import org.bukkit.block.Block;
            import org.bukkit.plugin.Plugin;
            public class Test {
                void test(Plugin plugin, Location loc, Block block) {
                    Bukkit.getRegionScheduler().run(plugin, loc, task -> {
                        block.setType(null);
                    });
                }
            }
            """.trimIndent()
        )
        assertNoFoliaHighlights()
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

    private fun assertNoFoliaHighlights() {
        val highlights = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WARNING.myVal }
            .filter { it.description?.contains("Accessing") == true }
        assertTrue(
            "Expected no Folia cross-context warnings, but found: " +
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

    private fun addFoliaMocks() {
        // Consumer functional interface
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

        // Entity hierarchy
        myFixture.addClass(
            """
            package org.bukkit.entity;
            import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
            public interface Entity {
                EntityScheduler getScheduler();
                String getCustomName();
                void setCustomName(String name);
                void remove();
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.bukkit.entity;
            public interface LivingEntity extends Entity {
                double getHealth();
                void setHealth(double health);
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.bukkit.entity;
            public interface Player extends LivingEntity {
                void sendMessage(String message);
            }
            """.trimIndent()
        )

        // Block
        myFixture.addClass(
            """
            package org.bukkit.block;
            public interface Block {
                void setType(Object type);
                Object getType();
            }
            """.trimIndent()
        )

        // Chunk
        myFixture.addClass(
            """
            package org.bukkit;
            public interface Chunk {
                Object[] getEntities();
            }
            """.trimIndent()
        )

        // World
        myFixture.addClass(
            """
            package org.bukkit;
            public interface World {
                Object getBlockAt(int x, int y, int z);
            }
            """.trimIndent()
        )
    }
}
