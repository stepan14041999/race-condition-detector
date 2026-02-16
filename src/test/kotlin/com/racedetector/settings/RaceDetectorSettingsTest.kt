package com.racedetector.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.jupiter.api.Assertions.*

class RaceDetectorSettingsTest : BasePlatformTestCase() {

    private lateinit var originalState: RaceDetectorSettings.State

    override fun setUp() {
        super.setUp()
        // Save original state
        val settings = RaceDetectorSettings.getInstance()
        originalState = settings.state.copy(
            customThreadAnnotations = settings.state.customThreadAnnotations.toMutableList(),
            customSafeAnnotations = settings.state.customSafeAnnotations.toMutableList()
        )
    }

    override fun tearDown() {
        try {
            // Restore original state
            val settings = RaceDetectorSettings.getInstance()
            settings.loadState(originalState)
        } finally {
            super.tearDown()
        }
    }

    fun testDefaultSettings() {
        // Reset to defaults first
        val settings = RaceDetectorSettings.getInstance()
        val defaultState = RaceDetectorSettings.State()
        settings.loadState(defaultState)

        // Check default values
        assertEquals(4, settings.callchainDepth)
        assertEquals(RaceDetectorSettings.FoliaMode.AUTO_DETECT, settings.foliaEnabled)
        assertTrue(settings.enableSpringChecks)
        assertTrue(settings.enableAndroidChecks)

        // Check default custom annotations are present
        assertTrue(settings.customThreadAnnotations.isNotEmpty())
        assertTrue(settings.customSafeAnnotations.isNotEmpty())
    }

    fun testModifySettings() {
        val settings = RaceDetectorSettings.getInstance()
        val state = settings.state

        // Modify settings
        state.callchainDepth = 6
        state.foliaEnabled = RaceDetectorSettings.FoliaMode.ALWAYS_ON
        state.enableSpringChecks = false
        state.enableAndroidChecks = false
        state.customThreadAnnotations = mutableListOf("com.test.MyThreadAnnotation")
        state.customSafeAnnotations = mutableListOf("com.test.MySafeAnnotation")

        settings.loadState(state)

        // Verify changes
        assertEquals(6, settings.callchainDepth)
        assertEquals(RaceDetectorSettings.FoliaMode.ALWAYS_ON, settings.foliaEnabled)
        assertFalse(settings.enableSpringChecks)
        assertFalse(settings.enableAndroidChecks)
        assertEquals(listOf("com.test.MyThreadAnnotation"), settings.customThreadAnnotations)
        assertEquals(listOf("com.test.MySafeAnnotation"), settings.customSafeAnnotations)
    }

    fun testFoliaModeValues() {
        val settings = RaceDetectorSettings.getInstance()
        val state = settings.state

        // Test all Folia modes
        state.foliaEnabled = RaceDetectorSettings.FoliaMode.AUTO_DETECT
        settings.loadState(state)
        assertEquals(RaceDetectorSettings.FoliaMode.AUTO_DETECT, settings.foliaEnabled)

        state.foliaEnabled = RaceDetectorSettings.FoliaMode.ALWAYS_ON
        settings.loadState(state)
        assertEquals(RaceDetectorSettings.FoliaMode.ALWAYS_ON, settings.foliaEnabled)

        state.foliaEnabled = RaceDetectorSettings.FoliaMode.ALWAYS_OFF
        settings.loadState(state)
        assertEquals(RaceDetectorSettings.FoliaMode.ALWAYS_OFF, settings.foliaEnabled)
    }

    fun testCallchainDepthBoundaries() {
        val settings = RaceDetectorSettings.getInstance()
        val state = settings.state

        // Test minimum depth
        state.callchainDepth = 1
        settings.loadState(state)
        assertEquals(1, settings.callchainDepth)

        // Test maximum depth
        state.callchainDepth = 10
        settings.loadState(state)
        assertEquals(10, settings.callchainDepth)
    }

    fun testCustomAnnotationsList() {
        val settings = RaceDetectorSettings.getInstance()
        val state = settings.state

        // Test empty lists
        state.customThreadAnnotations = mutableListOf()
        state.customSafeAnnotations = mutableListOf()
        settings.loadState(state)
        assertTrue(settings.customThreadAnnotations.isEmpty())
        assertTrue(settings.customSafeAnnotations.isEmpty())

        // Test multiple annotations
        state.customThreadAnnotations = mutableListOf(
            "com.test.Annotation1",
            "com.test.Annotation2",
            "com.test.Annotation3"
        )
        settings.loadState(state)
        assertEquals(3, settings.customThreadAnnotations.size)
        assertTrue(settings.customThreadAnnotations.contains("com.test.Annotation1"))
        assertTrue(settings.customThreadAnnotations.contains("com.test.Annotation2"))
        assertTrue(settings.customThreadAnnotations.contains("com.test.Annotation3"))
    }
}
