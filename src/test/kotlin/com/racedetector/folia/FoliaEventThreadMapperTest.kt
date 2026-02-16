package com.racedetector.folia

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.racedetector.analysis.ThreadContext
import com.racedetector.threading.ThreadContextResolver

class FoliaEventThreadMapperTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        addBukkitEventApiMocks()
    }

    /**
     * Adds mock Bukkit event hierarchy classes needed for event handler resolution.
     */
    private fun addBukkitEventApiMocks() {
        // @EventHandler annotation
        myFixture.addClass(
            """
            package org.bukkit.event;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface EventHandler {}
            """.trimIndent()
        )

        // Base Event class
        myFixture.addClass(
            """
            package org.bukkit.event;
            public abstract class Event {}
            """.trimIndent()
        )

        // PlayerEvent hierarchy
        myFixture.addClass(
            """
            package org.bukkit.event.player;
            import org.bukkit.event.Event;
            public abstract class PlayerEvent extends Event {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.bukkit.event.player;
            public class PlayerJoinEvent extends PlayerEvent {}
            """.trimIndent()
        )

        // BlockEvent hierarchy
        myFixture.addClass(
            """
            package org.bukkit.event.block;
            import org.bukkit.event.Event;
            public abstract class BlockEvent extends Event {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.bukkit.event.block;
            public class BlockBreakEvent extends BlockEvent {}
            """.trimIndent()
        )

        // ServerEvent hierarchy
        myFixture.addClass(
            """
            package org.bukkit.event.server;
            import org.bukkit.event.Event;
            public abstract class ServerEvent extends Event {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.bukkit.event.server;
            public class ServerLoadEvent extends ServerEvent {}
            """.trimIndent()
        )

        // InventoryEvent hierarchy
        myFixture.addClass(
            """
            package org.bukkit.event.inventory;
            import org.bukkit.event.Event;
            public class InventoryEvent extends Event {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.bukkit.event.inventory;
            public class InventoryClickEvent extends InventoryEvent {}
            """.trimIndent()
        )

        // WorldEvent hierarchy
        myFixture.addClass(
            """
            package org.bukkit.event.world;
            import org.bukkit.event.Event;
            public abstract class WorldEvent extends Event {}
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

    // 1. @EventHandler with PlayerJoinEvent → FoliaEntityThread
    fun testPlayerJoinEventHandler() {
        enableFoliaDetection()
        myFixture.configureByText(
            "TestListener.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.player.PlayerJoinEvent;
            public class TestListener {
                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    int x = <caret>42;
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

    // 2. @EventHandler with BlockBreakEvent → FoliaRegionThread
    fun testBlockBreakEventHandler() {
        enableFoliaDetection()
        myFixture.configureByText(
            "TestListener.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.block.BlockBreakEvent;
            public class TestListener {
                @EventHandler
                public void onBreak(BlockBreakEvent event) {
                    int x = <caret>42;
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

    // 3. @EventHandler with ServerLoadEvent → FoliaGlobalThread
    fun testServerLoadEventHandler() {
        enableFoliaDetection()
        myFixture.configureByText(
            "TestListener.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.server.ServerLoadEvent;
            public class TestListener {
                @EventHandler
                public void onLoad(ServerLoadEvent event) {
                    int x = <caret>42;
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.FoliaGlobalThread)
    }

    // 4. @EventHandler with InventoryClickEvent → FoliaEntityThread
    fun testInventoryClickEventHandler() {
        enableFoliaDetection()
        myFixture.configureByText(
            "TestListener.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.inventory.InventoryClickEvent;
            public class TestListener {
                @EventHandler
                public void onClick(InventoryClickEvent event) {
                    int x = <caret>42;
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

    // 5. Method without @EventHandler → not applicable (Unknown)
    fun testMethodWithoutEventHandlerAnnotation() {
        enableFoliaDetection()
        myFixture.configureByText(
            "TestListener.java",
            """
            import org.bukkit.event.player.PlayerJoinEvent;
            public class TestListener {
                public void onJoin(PlayerJoinEvent event) {
                    int x = <caret>42;
                }
            }
            """.trimIndent()
        )
        assertContext(ThreadContext.Unknown)
    }

    // 6. Non-Folia project → not applicable (Unknown)
    fun testNonFoliaProjectIgnoresEventHandler() {
        // No enableFoliaDetection() — RegionizedServer is absent
        myFixture.configureByText(
            "TestListener.java",
            """
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.player.PlayerJoinEvent;
            public class TestListener {
                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    int x = <caret>42;
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
