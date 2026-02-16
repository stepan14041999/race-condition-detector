package com.racedetector.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "RaceDetectorSettings",
    storages = [Storage("raceDetectorSettings.xml")]
)
class RaceDetectorSettings : PersistentStateComponent<RaceDetectorSettings.State> {

    private var myState = State()

    data class State(
        var customThreadAnnotations: MutableList<String> = mutableListOf(
            "android.support.annotation.WorkerThread",
            "androidx.annotation.WorkerThread",
            "android.support.annotation.MainThread",
            "androidx.annotation.MainThread",
            "android.support.annotation.UiThread",
            "androidx.annotation.UiThread"
        ),
        var customSafeAnnotations: MutableList<String> = mutableListOf(
            "org.springframework.context.annotation.Scope"
        ),
        var foliaEnabled: FoliaMode = FoliaMode.AUTO_DETECT,
        var callchainDepth: Int = 4,
        var enableSpringChecks: Boolean = true,
        var enableAndroidChecks: Boolean = true
    )

    enum class FoliaMode {
        AUTO_DETECT,
        ALWAYS_ON,
        ALWAYS_OFF
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): RaceDetectorSettings {
            return ApplicationManager.getApplication().getService(RaceDetectorSettings::class.java)
        }
    }

    val customThreadAnnotations: List<String>
        get() = myState.customThreadAnnotations.toList()

    val customSafeAnnotations: List<String>
        get() = myState.customSafeAnnotations.toList()

    val foliaEnabled: FoliaMode
        get() = myState.foliaEnabled

    val callchainDepth: Int
        get() = myState.callchainDepth

    val enableSpringChecks: Boolean
        get() = myState.enableSpringChecks

    val enableAndroidChecks: Boolean
        get() = myState.enableAndroidChecks
}
