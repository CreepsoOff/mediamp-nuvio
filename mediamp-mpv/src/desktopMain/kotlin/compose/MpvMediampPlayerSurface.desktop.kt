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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.window.LocalWindow
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

    var textureId by remember(player) { mutableIntStateOf(0) }
    var renderContextInitialized by remember(player) { mutableStateOf(false) }
    var lastContextSignature by remember(player) { mutableStateOf<String?>(null) }
    val interpolator = remember(player) { FrameInterpolator() }

    // Bind the render context to BOTH the GL components and the player. When
    // the upstream wrapper recreates the player (source / episode switch keyed
    // on the stream identity) the components instance can be the same window,
    // so keying only on `components` would skip the render-context creation
    // for the new player and leave it rendering nowhere. Keying on the player
    // ensures every fresh MpvMediampPlayer gets its own render context bound
    // to the current SkiaLayer GL device/context.
    DisposableEffect(components, player) {
        if (components == null) return@DisposableEffect onDispose { }

        // Reset Skia's cached GL state before the new MPV render context binds
        // to the shared OpenGL context. Without this, residual bindings from a
        // previously-disposed player can keep the new player's first frames
        // from making it onto the screen (black surface with audio playing).
        runCatching { components.directContext.resetGLAll() }

        player.createRenderContext(components.glDevice, components.glContext)
        lastContextSignature = components.contextSignature
        renderContextInitialized = true

        onDispose {
            player.image?.close()
            player.image = null
            player.backendTexture?.close()
            player.backendTexture = null
            player.releaseTexture()
            player.releaseRenderContext()
            // Tell Skia to forget any GL state it captured while this player
            // owned the render context. The next player on the same window
            // will start from a clean cache instead of inheriting stale FBO /
            // texture bindings from this disposed player.
            runCatching { components.directContext.resetGLAll() }
            renderContextInitialized = false
            lastContextSignature = null
            textureId = 0
        }
    }

    LaunchedEffect(interpolator) {
        interpolator.frameLoop()
    }

    Canvas(modifier = modifier) {
        interpolator.updateSubscription

        if (!renderContextInitialized || components == null) return@Canvas
        val skiaCanvas = drawContext.canvas.nativeCanvas
        val currentContextSignature = components.contextSignature

        if (lastContextSignature != null && lastContextSignature != currentContextSignature) {
            runCatching { player.releaseRenderContext() }
            runCatching { components.directContext.resetGLAll() }
            runCatching { player.createRenderContext(components.glDevice, components.glContext) }
                .onSuccess { renderContextInitialized = true }
                .onFailure {
                    renderContextInitialized = false
                }
        }
        lastContextSignature = currentContextSignature

        val sizeChanged = player.currentSize != null && player.currentSize != size
        if (player.currentSize == null || player.currentSize != size || textureId == 0) {
            val targetWidth = size.width.toInt()
            val targetHeight = size.height.toInt()
            if (targetWidth <= 0 || targetHeight <= 0) {
                player.currentSize = null
                return@Canvas
            }
            if (sizeChanged) {
                val releaseContextResult = runCatching { player.releaseRenderContext() }.getOrElse { false }
                runCatching { components.directContext.resetGLAll() }
                val recreateContextResult = runCatching {
                    player.createRenderContext(components.glDevice, components.glContext)
                }.getOrElse { false }
                renderContextInitialized = recreateContextResult
                if (!recreateContextResult) {
                    textureId = 0
                    player.currentSize = null
                    return@Canvas
                }
            }
            runCatching { player.releaseTexture() }.getOrElse { false }

            player.image?.close()
            player.image = null
            player.backendTexture?.close()
            player.backendTexture = null

            // Drop any leftover GL state from the previous size or from a
            // sibling player so the new texture/FBO is bound on a clean slate.
            // Required to recover from manual window drag-resizes which were
            // causing the surface to render to a stale FBO target.
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
                // Texture creation failed — leave currentSize null so the next
                // frame retries instead of getting stuck rendering nothing.
                player.currentSize = null
            }
        }

        if (textureId != 0) {
            val renderResult = runCatching { player.renderFrame() }
                .getOrDefault(false)
            if (!renderResult) return@Canvas
            runCatching { components.directContext.resetGLAll() }
        }
        player.image?.let {
            skiaCanvas.drawImage(it, 0f, 0f)
        }
    }
}
