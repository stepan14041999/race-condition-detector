package com.racedetector.folia

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FoliaProjectDetectorTest : LightJavaCodeInsightFixtureTestCase() {

    /**
     * Project with Folia marker class in classpath → detected as Folia.
     */
    fun testFoliaProjectDetectedByClasspath() {
        myFixture.addClass(
            """
            package io.papermc.paper.threadedregions;
            public class RegionizedServer {}
            """.trimIndent()
        )

        assertTrue(
            "Project with RegionizedServer in classpath should be detected as Folia",
            FoliaProjectDetector.isFoliaProject(project)
        )
    }

    /**
     * Project with Bukkit/Spigot API but no Folia classes → NOT Folia.
     */
    fun testNonFoliaProjectWithBukkitApi() {
        myFixture.addClass(
            """
            package org.bukkit;
            public class Bukkit {
                public static Object getServer() { return null; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package org.bukkit.plugin.java;
            public abstract class JavaPlugin {}
            """.trimIndent()
        )

        assertFalse(
            "Project with Bukkit API but no Folia classes should NOT be detected as Folia",
            FoliaProjectDetector.isFoliaProject(project)
        )
    }

    /**
     * Empty project without any Bukkit or Folia API → NOT Folia.
     */
    fun testProjectWithoutBukkitApi() {
        assertFalse(
            "Project without any Bukkit/Folia API should NOT be detected as Folia",
            FoliaProjectDetector.isFoliaProject(project)
        )
    }
}
