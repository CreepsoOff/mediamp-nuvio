/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.openani.mediamp.mpv.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.window.LocalWindow
import kotlinx.coroutines.delay
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.SurfaceOrigin
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.utils.OpenGLComponentProvider
import org.openani.mediamp.mpv.utils.findSkiaLayer

@OptIn(InternalMediampApi::class)
@Composable
actual fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current as ComposeWindow
    val components = remember(window) {
        window.findSkiaLayer()?.let { OpenGLComponentProvider(it) }
    }

    var textureId by remember { mutableIntStateOf(0) }
    var renderContextInitialized by remember { mutableStateOf(false) }
    var lastTextureSize by remember { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    var frameCount by remember { mutableLongStateOf(0L) }

    // Rendering loop: ~60fps, independent of Compose frame scheduling
    LaunchedEffect(Unit) {
        while (true) {
            frameCount++
            delay(16L)
        }
    }

    // Initialize/destroy mpv render context (once per components lifecycle)
    DisposableEffect(components) {
        if (components == null) return@DisposableEffect onDispose { }

        runCatching { components.directContext.resetGLAll() }
        runCatching { player.createRenderContext(components.glDevice, components.glContext) }
            .onSuccess { renderContextInitialized = true }
            .onFailure { renderContextInitialized = false }

        onDispose {
            player.image?.close()
            player.image = null
            player.backendTexture?.close()
            player.backendTexture = null
            runCatching { player.releaseTexture() }
            runCatching { player.releaseRenderContext() }
            runCatching { components.directContext.resetGLAll() }
            renderContextInitialized = false
            textureId = 0
            lastTextureSize = null
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_EXPRESSION")
        frameCount

        if (!renderContextInitialized || components == null) return@Canvas

        val skiaCanvas = drawContext.canvas.nativeCanvas
        val targetWidth = size.width.toInt().coerceAtLeast(1)
        val targetHeight = size.height.toInt().coerceAtLeast(1)
        val currentSize = androidx.compose.ui.unit.IntSize(targetWidth, targetHeight)

        // Recreate FBO/texture only when size changes or no valid texture
        val sizeChanged = lastTextureSize != null && lastTextureSize != currentSize
        if (lastTextureSize == null || sizeChanged || textureId == 0) {
            // Release old Skia objects
            player.image?.close()
            player.image = null
            player.backendTexture?.close()
            player.backendTexture = null

            // Release old FBO (keeps GL texture alive for Skia to clean up)
            runCatching { player.releaseTexture() }

            // Create new FBO with current size
            val newTextureId = player.createTexture(targetWidth, targetHeight)
            if (newTextureId == 0) {
                textureId = 0
                lastTextureSize = null
                return@Canvas
            }

            // Wrap GL texture in Skia objects for Compose rendering
            val backendTexture = runCatching {
                BackendTexture.makeGL(
                    width = targetWidth,
                    height = targetHeight,
                    isMipmapped = false,
                    textureId = newTextureId,
                    textureTarget = MpvMediampPlayer.GL_TEXTURE_2D,
                    textureFormat = MpvMediampPlayer.GL_RGBA8,
                )
            }.getOrNull()

            if (backendTexture == null) {
                textureId = 0
                lastTextureSize = null
                return@Canvas
            }

            player.backendTexture = backendTexture

            val adoptedImage = runCatching {
                Image.adoptTextureFrom(
                    context = components.directContext,
                    backendTexture = backendTexture,
                    origin = SurfaceOrigin.TOP_LEFT,
                    colorType = ColorType.RGBA_8888,
                )
            }.getOrNull()

            if (adoptedImage == null) {
                textureId = 0
                lastTextureSize = null
                return@Canvas
            }

            player.image = adoptedImage
            textureId = newTextureId
            lastTextureSize = currentSize
        }

        // Render mpv frame into FBO and draw to Compose canvas
        if (textureId != 0) {
            val renderOk = runCatching { player.renderFrame() }.getOrDefault(false)
            if (renderOk) {
                runCatching { components.directContext.resetGLAll() }
            }
        }

        player.image?.let {
            skiaCanvas.drawImage(it, 0f, 0f)
        }
    }
}
