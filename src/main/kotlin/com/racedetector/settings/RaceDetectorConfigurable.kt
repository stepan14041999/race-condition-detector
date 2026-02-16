package com.racedetector.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class RaceDetectorConfigurable : Configurable {

    private val settings = RaceDetectorSettings.getInstance()
    private lateinit var threadAnnotationsText: String
    private lateinit var safeAnnotationsText: String
    private lateinit var foliaMode: RaceDetectorSettings.FoliaMode
    private var callchainDepth: Int = 4
    private var maxParentTraversalDepth: Int = 50
    private var enableSpringChecks: Boolean = true
    private var enableAndroidChecks: Boolean = true

    override fun getDisplayName(): String = "Race Condition Detector"

    override fun createComponent(): JComponent {
        // Load current settings
        threadAnnotationsText = settings.customThreadAnnotations.joinToString("\n")
        safeAnnotationsText = settings.customSafeAnnotations.joinToString("\n")
        foliaMode = settings.foliaEnabled
        callchainDepth = settings.callchainDepth
        maxParentTraversalDepth = settings.maxParentTraversalDepth
        enableSpringChecks = settings.enableSpringChecks
        enableAndroidChecks = settings.enableAndroidChecks

        return panel {
            group("Thread Annotations") {
                row {
                    label("Custom thread context annotations (one per line):")
                }
                row {
                    textArea()
                        .rows(5)
                        .columns(60)
                        .bindText(::threadAnnotationsText)
                        .comment(
                            "Add custom annotations that mark methods as running in specific thread contexts.<br>" +
                            "Example: com.myapp.annotations.BackgroundThread"
                        )
                }
            }

            group("Thread Safety Annotations") {
                row {
                    label("Custom thread-safe annotations (one per line):")
                }
                row {
                    textArea()
                        .rows(5)
                        .columns(60)
                        .bindText(::safeAnnotationsText)
                        .comment(
                            "Add custom annotations that mark classes/fields as thread-safe.<br>" +
                            "Example: com.myapp.annotations.ThreadSafe"
                        )
                }
            }

            group("Analysis Options") {
                buttonsGroup("Folia support:") {
                    row {
                        radioButton("Auto-detect", RaceDetectorSettings.FoliaMode.AUTO_DETECT)
                        radioButton("Always on", RaceDetectorSettings.FoliaMode.ALWAYS_ON)
                        radioButton("Always off", RaceDetectorSettings.FoliaMode.ALWAYS_OFF)
                    }
                }.bind(::foliaMode)
                row {
                    comment("Auto-detect checks classpath for Folia API presence")
                }

                row("Call chain analysis depth:") {
                    spinner(1..10)
                        .bindIntValue(::callchainDepth)
                        .comment("Maximum depth for cross-method caller chain analysis (default: 4)")
                }

                row("Parent traversal depth:") {
                    spinner(10..200)
                        .bindIntValue(::maxParentTraversalDepth)
                        .comment("Maximum PSI/UAST parent chain depth within a file (default: 50)")
                }
            }

            group("Framework-Specific Checks") {
                row {
                    checkBox("Enable Spring Framework checks")
                        .bindSelected(::enableSpringChecks)
                        .comment("Detect issues in Spring @Controller, @Service, @Async methods")
                }
                row {
                    checkBox("Enable Android checks")
                        .bindSelected(::enableAndroidChecks)
                        .comment("Detect issues with @MainThread, @WorkerThread, @UiThread")
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val currentThreadAnnotations = threadAnnotationsText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val currentSafeAnnotations = safeAnnotationsText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return currentThreadAnnotations != settings.customThreadAnnotations ||
                currentSafeAnnotations != settings.customSafeAnnotations ||
                foliaMode != settings.foliaEnabled ||
                callchainDepth != settings.callchainDepth ||
                maxParentTraversalDepth != settings.maxParentTraversalDepth ||
                enableSpringChecks != settings.enableSpringChecks ||
                enableAndroidChecks != settings.enableAndroidChecks
    }

    override fun apply() {
        val state = settings.state

        state.customThreadAnnotations = threadAnnotationsText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        state.customSafeAnnotations = safeAnnotationsText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        state.foliaEnabled = foliaMode
        state.callchainDepth = callchainDepth
        state.maxParentTraversalDepth = maxParentTraversalDepth
        state.enableSpringChecks = enableSpringChecks
        state.enableAndroidChecks = enableAndroidChecks

        settings.loadState(state)
    }

    override fun reset() {
        threadAnnotationsText = settings.customThreadAnnotations.joinToString("\n")
        safeAnnotationsText = settings.customSafeAnnotations.joinToString("\n")
        foliaMode = settings.foliaEnabled
        callchainDepth = settings.callchainDepth
        maxParentTraversalDepth = settings.maxParentTraversalDepth
        enableSpringChecks = settings.enableSpringChecks
        enableAndroidChecks = settings.enableAndroidChecks
    }
}
