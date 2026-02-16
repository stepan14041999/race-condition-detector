package com.racedetector.folia

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FoliaSharedStateInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(FoliaSharedStateInspection())
        addFoliaMocks()
        enableFoliaDetection()
    }

    // ==================== FR-6.3: Shared state in Listener fields ====================

    /**
     * Test 1: HashMap field accessed from two @EventHandler methods (PlayerJoinEvent + PlayerQuitEvent)
     * → ERROR: different players run in different threads in Folia
     */
    fun testHashMapFieldInListenerWithTwoHandlers() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.Listener;
            import org.bukkit.event.player.PlayerJoinEvent;
            import org.bukkit.event.player.PlayerQuitEvent;
            import java.util.HashMap;
            import java.util.UUID;

            public class Test implements Listener {
                private HashMap<UUID, Integer> playerScores = new HashMap<>();

                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    playerScores.put(event.getPlayer().getUniqueId(), 0);
                }

                @EventHandler
                public void onQuit(PlayerQuitEvent event) {
                    playerScores.remove(event.getPlayer().getUniqueId());
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("Field 'playerScores' is accessed from multiple @EventHandler methods", HighlightSeverity.ERROR)
    }

    /**
     * Test 2: ConcurrentHashMap field → no warning (thread-safe type)
     */
    fun testConcurrentHashMapFieldInListenerIsOk() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.Listener;
            import org.bukkit.event.player.PlayerJoinEvent;
            import org.bukkit.event.player.PlayerQuitEvent;
            import java.util.concurrent.ConcurrentHashMap;
            import java.util.UUID;

            public class Test implements Listener {
                private ConcurrentHashMap<UUID, Integer> playerScores = new ConcurrentHashMap<>();

                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    playerScores.put(event.getPlayer().getUniqueId(), 0);
                }

                @EventHandler
                public void onQuit(PlayerQuitEvent event) {
                    playerScores.remove(event.getPlayer().getUniqueId());
                }
            }
            """.trimIndent()
        )
        assertNoSharedStateHighlights()
    }

    /**
     * Test 3: volatile int field accessed from two handlers → no warning (volatile is thread-safe)
     */
    fun testVolatileFieldInListenerIsOk() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.Listener;
            import org.bukkit.event.player.PlayerJoinEvent;
            import org.bukkit.event.player.PlayerQuitEvent;

            public class Test implements Listener {
                private volatile int counter = 0;

                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    counter++;
                }

                @EventHandler
                public void onQuit(PlayerQuitEvent event) {
                    counter--;
                }
            }
            """.trimIndent()
        )
        assertNoSharedStateHighlights()
    }

    // ==================== FR-6.4: Shared state in plugin class ====================

    /**
     * Test 4: ArrayList field in JavaPlugin subclass accessed from two handlers → ERROR
     */
    fun testArrayListFieldInPluginClass() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.Listener;
            import org.bukkit.event.player.PlayerJoinEvent;
            import org.bukkit.event.player.PlayerQuitEvent;
            import org.bukkit.entity.Player;
            import org.bukkit.plugin.java.JavaPlugin;
            import java.util.ArrayList;

            public class Test extends JavaPlugin implements Listener {
                private ArrayList<Player> onlinePlayers = new ArrayList<>();

                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    onlinePlayers.add(event.getPlayer());
                }

                @EventHandler
                public void onQuit(PlayerQuitEvent event) {
                    onlinePlayers.remove(event.getPlayer());
                }
            }
            """.trimIndent()
        )
        assertHasHighlight("Field 'onlinePlayers' is accessed from multiple @EventHandler methods", HighlightSeverity.ERROR)
    }

    /**
     * Test 5: Field accessed from only one @EventHandler method → no warning
     */
    fun testFieldAccessedFromSingleHandlerIsOk() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.Listener;
            import org.bukkit.event.player.PlayerJoinEvent;
            import java.util.HashMap;
            import java.util.UUID;

            public class Test implements Listener {
                private HashMap<UUID, Integer> playerScores = new HashMap<>();

                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    playerScores.put(event.getPlayer().getUniqueId(), 0);
                }

                public void someHelper() {
                    playerScores.clear();
                }
            }
            """.trimIndent()
        )
        assertNoSharedStateHighlights()
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

    private fun assertNoSharedStateHighlights() {
        val highlights = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WARNING.myVal }
            .filter { it.description?.contains("is accessed from multiple @EventHandler") == true }
        assertTrue(
            "Expected no shared state warnings, but found: " +
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
        // java.util types
        myFixture.addClass(
            """
            package java.util;
            public class HashMap<K, V> implements java.util.Map<K, V> {
                public V put(K key, V value) { return null; }
                public V get(Object key) { return null; }
                public V remove(Object key) { return null; }
                public void clear() {}
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package java.util;
            public class ArrayList<E> implements java.util.List<E> {
                public boolean add(E e) { return true; }
                public boolean remove(Object o) { return true; }
                public void clear() {}
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package java.util;
            public class UUID {
                public static UUID randomUUID() { return null; }
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package java.util.concurrent;
            public class ConcurrentHashMap<K, V> implements java.util.Map<K, V> {
                public V put(K key, V value) { return null; }
                public V get(Object key) { return null; }
                public V remove(Object key) { return null; }
            }
            """.trimIndent()
        )

        // Bukkit event hierarchy
        myFixture.addClass(
            """
            package org.bukkit.event;
            public abstract class Event {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.event;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface EventHandler {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.event;
            public interface Listener {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.event.player;
            import org.bukkit.event.Event;
            import org.bukkit.entity.Player;
            public class PlayerEvent extends Event {
                public Player getPlayer() { return null; }
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.event.player;
            public class PlayerJoinEvent extends PlayerEvent {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.event.player;
            public class PlayerQuitEvent extends PlayerEvent {}
            """.trimIndent()
        )

        // Entity hierarchy
        myFixture.addClass(
            """
            package org.bukkit.entity;
            public interface Entity {
                java.util.UUID getUniqueId();
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.entity;
            public interface LivingEntity extends Entity {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.entity;
            public interface Player extends LivingEntity {
                void sendMessage(String message);
                java.util.UUID getUniqueId();
            }
            """.trimIndent()
        )

        // Plugin
        myFixture.addClass(
            """
            package org.bukkit.plugin;
            public interface Plugin {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.bukkit.plugin.java;
            import org.bukkit.plugin.Plugin;
            public abstract class JavaPlugin implements Plugin {}
            """.trimIndent()
        )
    }
}
