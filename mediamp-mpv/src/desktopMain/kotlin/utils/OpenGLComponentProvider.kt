/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.utils

import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.context.ContextHandler
import org.jetbrains.skiko.context.OpenGLContextHandler

class OpenGLComponentProvider(skiaLayer: SkiaLayer) {
    private val redrawer: Any = skiaLayer.redrawer!!

    private val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

    private val deviceHandleField = redrawer::class.java
        .getDeclaredField("device")
        .also { it.isAccessible = true }

    private val glContextHandleField = redrawer::class.java
        .getDeclaredField("context")
        .also { it.isAccessible = true }

    private val contextHandlerHandleField = redrawer::class.java
        .getDeclaredField("contextHandler")
        .also { it.isAccessible = true }
    private val directContextHandler = ContextHandler::class.java
        .getDeclaredField("context")
        .also { it.isAccessible = true }

    val glDevice: Long get() = deviceHandleField.getLong(redrawer)
    val glContext: Long get() = glContextHandleField.getLong(redrawer)
    val contextSignature: String get() = "$glDevice:$glContext"

    val directContext: DirectContext
        get() = (contextHandlerHandleField.get(redrawer) as OpenGLContextHandler)
            .let { directContextHandler.get(it) as DirectContext }
}
