package com.racedetector.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CustomSafeAnnotationTest : BasePlatformTestCase() {

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

    fun testCustomSafeAnnotations() {
        val settings = RaceDetectorSettings.getInstance()
        val state = settings.state

        // Add custom safe annotations
        state.customSafeAnnotations = mutableListOf(
            "com.myapp.ThreadSafeField",
            "com.myapp.Immutable"
        )
        settings.loadState(state)

        // Verify settings were updated
        assertEquals(2, settings.customSafeAnnotations.size)
        assertTrue(settings.customSafeAnnotations.contains("com.myapp.ThreadSafeField"))
        assertTrue(settings.customSafeAnnotations.contains("com.myapp.Immutable"))
    }

    fun testCustomSafeAnnotationsEmpty() {
        val settings = RaceDetectorSettings.getInstance()
        val state = settings.state

        // Clear custom annotations
        state.customSafeAnnotations = mutableListOf()
        settings.loadState(state)

        // Verify settings were cleared
        assertTrue(settings.customSafeAnnotations.isEmpty())
    }
}
