package com.racedetector.folia

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.racedetector.settings.RaceDetectorSettings

object FoliaProjectDetector {

    private val FOLIA_MARKER_CLASSES = listOf(
        "io.papermc.paper.threadedregions.RegionizedServer",
        "io.papermc.paper.threadedregions.RegionizedData"
    )

    private val FOLIA_DEPENDENCY_MARKERS = listOf("dev.folia", "folia-api")

    private val BUILD_FILE_NAMES = listOf("build.gradle.kts", "build.gradle", "pom.xml")

    fun isFoliaProject(project: Project): Boolean {
        val settings = RaceDetectorSettings.getInstance()
        return when (settings.foliaEnabled) {
            RaceDetectorSettings.FoliaMode.ALWAYS_ON -> true
            RaceDetectorSettings.FoliaMode.ALWAYS_OFF -> false
            RaceDetectorSettings.FoliaMode.AUTO_DETECT -> {
                CachedValuesManager.getManager(project).getCachedValue(project) {
                    val result = checkClasspath(project) || checkBuildFiles(project)
                    CachedValueProvider.Result.create(
                        result,
                        ProjectRootModificationTracker.getInstance(project)
                    )
                }
            }
        }
    }

    private fun checkClasspath(project: Project): Boolean {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        return FOLIA_MARKER_CLASSES.any { facade.findClass(it, scope) != null }
    }

    private fun checkBuildFiles(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        val vfs = LocalFileSystem.getInstance()

        for (fileName in BUILD_FILE_NAMES) {
            val vFile = vfs.findFileByPath("$basePath/$fileName") ?: continue
            val content = String(vFile.contentsToByteArray(), Charsets.UTF_8)
            if (FOLIA_DEPENDENCY_MARKERS.any { marker -> content.contains(marker) }) {
                return true
            }
        }
        return false
    }
}
