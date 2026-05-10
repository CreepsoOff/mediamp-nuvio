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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    var lastContextSignature by remember { mutableStateOf<String?>(null) }
    var frameCount by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            frameCount++
            delay(16L)
        }
    }

    DisposableEffect(components, player) {
        if (components == null) return@DisposableEffect onDispose { }

        runCatching { components.directContext.resetGLAll() }

        runCatching { player.createRenderContext(components.glDevice, components.glContext) }
            .onSuccess {
                lastContextSignature = components.contextSignature
                renderContextInitialized = true
            }
            .onFailure { renderContextInitialized = false }

        onDispose {
            player.image?.close()
            player.image = null
            player.backendTexture?.close()
            player.backendTexture = null
            player.releaseTexture()
            player.releaseRenderContext()
            runCatching { components.directContext.resetGLAll() }
            renderContextInitialized = false
            lastContextSignature = null
            textureId = 0
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_EXPRESSION")
        frameCount

        if (!renderContextInitialized || components == null) return@Canvas
        val skiaCanvas = drawContext.canvas.nativeCanvas
        val currentContextSignature = components.contextSignature

        if (lastContextSignature != null && lastContextSignature != currentContextSignature) {
            runCatching { components.directContext.resetGLAll() }
            runCatching { player.createRenderContext(components.glDevice, components.glContext) }
                .onSuccess { renderContextInitialized = true }
                .onFailure { renderContextInitialized = false }
        }
        lastContextSignature = currentContextSignature

        val sizeChanged = player.currentSize != null && player.currentSize != size
        if (player.currentSize == null || sizeChanged || textureId == 0) {
            val targetWidth = size.width.toInt()
            val targetHeight = size.height.toInt()
            if (targetWidth <= 0 || targetHeight <= 0) {
                player.currentSize = null
                return@Canvas
            }

            if (sizeChanged) {
                runCatching { player.releaseRenderContext() }
                runCatching { components.directContext.resetGLAll() }
                val recreated = runCatching {
                    player.createRenderContext(components.glDevice, components.glContext)
                }.getOrElse { false }
                renderContextInitialized = recreated
                if (!recreated) {
                    textureId = 0
                    player.currentSize = null
                    return@Canvas
                }
            }

            runCatching { player.releaseTexture() }

            player.image?.close()
            player.image = null
            player.backendTexture?.close()
            player.backendTexture = null

            runCatching { components.directContext.resetGLAll() }

            textureId = 0
            val newTextureId = player.createTexture(targetWidth, targetHeight)

            if (newTextureId != 0) {
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
                    player.currentSize = null
                    textureId = 0
                } else {
                    player.backendTexture = backendTexture
                    val adoptedImage = runCatching {
                        Image.adoptTextureFrom(
                            context = components.directContext,
                            backendTexture = backendTexture,
                            origin = SurfaceOrigin.TOP_LEFT,
                            colorType = ColorType.RGBA_8888,
                        )
                    }.getOrNull()
                    player.image = adoptedImage
                    if (adoptedImage == null) {
                        textureId = 0
                        player.currentSize = null
                    } else {
                        textureId = newTextureId
                        player.currentSize = size
                    }
                }
            } else {
                player.currentSize = null
            }
        }

        if (textureId != 0) {
            val renderOk = runCatching { player.renderFrame() }.getOrDefault(false)
            if (!renderOk) return@Canvas
            runCatching { components.directContext.resetGLAll() }
        }
        player.image?.let {
            skiaCanvas.drawImage(it, 0f, 0f)
        }
    }
}
