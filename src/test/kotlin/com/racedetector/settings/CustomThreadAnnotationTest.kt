package com.racedetector.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.racedetector.threading.ThreadContextResolver
import com.racedetector.analysis.ThreadContext

class CustomThreadAnnotationTest : BasePlatformTestCase() {

    private lateinit var originalState: RaceDetectorSettings.State

    override fun setUp() {
        super.setUp()
        val settings = RaceDetectorSettings.getInstance()
        originalState = settings.state.copy(
            customThreadAnnotations = settings.state.customThreadAnnotations.toMutableList(),
            customSafeAnnotations = settings.state.customSafeAnnotations.toMutableList()
        )
    }

    override fun tearDown() {
        try {
            val settings = RaceDetectorSettings.getInstance()
            settings.loadState(originalState)
        } finally {
            super.tearDown()
        }
    }

    fun testCustomThreadAnnotations() {
        val settings = RaceDetectorSettings.getInstance()
        val state = settings.state

        // Add custom annotations
        state.customThreadAnnotations = mutableListOf(
            "com.myapp.BackgroundTask",
            "com.myapp.MainThreadOnly"
        )
        settings.loadState(state)

        // Verify settings were updated
        assertEquals(2, settings.customThreadAnnotations.size)
        assertTrue(settings.customThreadAnnotations.contains("com.myapp.BackgroundTask"))
        assertTrue(settings.customThreadAnnotations.contains("com.myapp.MainThreadOnly"))
    }

    fun testCustomThreadAnnotationsEmpty() {
        val settings = RaceDetectorSettings.getInstance()
        val state = settings.state

        // Clear custom annotations
        state.customThreadAnnotations = mutableListOf()
        settings.loadState(state)

        // Verify settings were cleared
        assertTrue(settings.customThreadAnnotations.isEmpty())
    }
}
