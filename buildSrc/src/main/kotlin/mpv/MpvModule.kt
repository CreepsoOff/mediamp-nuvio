package mpv

import org.gradle.api.Project

fun Project.configureMediampMpvModule() {
    evaluationDependsOn(":mediamp-ffmpeg")

    val context = MpvBuildContext(this)
    registerHostMpvTasks(context)
    val desktopRuntimeJarTasks = registerDesktopRuntimeJarTasks(context)
    val prepareTask = registerMpvAndroidJniPackaging(context)
    wireMpvAndroidJniPackaging(context, prepareTask)
    // configureRuntimePublishing disabled for composite build — creates variant ambiguity
    // with Kotlin Multiplatform desktopRuntimeElements
    if (!isCompositeBuild()) {
        configureRuntimePublishing(context, desktopRuntimeJarTasks)
    }
}

private fun Project.isCompositeBuild(): Boolean =
    gradle.parent != null
