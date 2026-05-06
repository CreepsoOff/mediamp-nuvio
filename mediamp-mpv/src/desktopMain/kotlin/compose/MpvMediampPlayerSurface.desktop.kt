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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
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
import java.awt.Canvas as AwtCanvas
import java.awt.Color as AwtColor
import javax.swing.JPanel
import java.awt.BorderLayout

@OptIn(InternalMediampApi::class)
@Composable
actual fun MpvMediampPlayerSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    val window = LocalWindow.current as ComposeWindow
    val components = remember(window) {
        window.findSkiaLayer()?.let { OpenGLComponentProvider.createOrNull(it) }
    }

    val isLinux = remember {
        System.getProperty("os.name")?.lowercase()?.contains("linux") == true
    }

    if (components == null && isLinux) {
        LinuxEmbeddedMpvSurface(player = player, modifier = modifier)
        return
    }

    GlBasedMpvSurface(player = player, modifier = modifier, components = components)
}

@OptIn(InternalMediampApi::class)
@Composable
private fun LinuxEmbeddedMpvSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
) {
    var windowId by remember(player) { mutableStateOf<Long?>(null) }
    var attached by remember(player) { mutableStateOf(false) }

    SwingPanel(
        modifier = modifier.fillMaxSize(),
        background = Color.Black,
        factory = {
            val panel = JPanel(BorderLayout()).apply {
                background = AwtColor.BLACK
                isOpaque = true
            }
            val canvas = AwtCanvas().apply {
                background = AwtColor.BLACK
            }
            panel.add(canvas, BorderLayout.CENTER)
            panel.addHierarchyListener {
                if (canvas.isDisplayable && !attached) {
                    try {
                        val peer = canvas::class.java.getMethod("getPeer")
                            .also { it.isAccessible = true }
                            .invoke(canvas)
                        if (peer != null) {
                            val getWindow = peer::class.java.getMethod("getWindow")
                                .also { it.isAccessible = true }
                            val xWindow = getWindow.invoke(peer) as? Long
                            if (xWindow != null && xWindow != 0L) {
                                windowId = xWindow
                            }
                        }
                    } catch (_: Exception) {
                        // Try ComponentPeer approach for newer JDKs
                        try {
                            val peerField = java.awt.Component::class.java
                                .getDeclaredField("peer")
                                .also { it.isAccessible = true }
                            val peer = peerField.get(canvas)
                            if (peer != null) {
                                val targetField = peer::class.java
                                    .getDeclaredField("target")
                                val getTargetMethod = peer::class.java.methods
                                    .firstOrNull { it.name == "getWindow" || it.name == "getContentWindow" }
                                if (getTargetMethod != null) {
                                    getTargetMethod.isAccessible = true
                                    val xid = getTargetMethod.invoke(peer) as? Long
                                    if (xid != null && xid != 0L) {
                                        windowId = xid
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            panel
        },
    )

    LaunchedEffect(windowId, player) {
        val wid = windowId ?: return@LaunchedEffect
        if (!attached) {
            attached = true
            runCatching {
                player.impl.option("vo", "x11,gpu,libmpv")
                player.impl.option("wid", wid.toString())
            }.onFailure {
                println("MPV_DESKTOP_SURFACE LinuxEmbedded wid set failed: ${it.message}")
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            attached = false
            runCatching {
                player.impl.option("wid", "0")
            }
        }
    }
}

@OptIn(InternalMediampApi::class)
@Composable
private fun GlBasedMpvSurface(
    player: MpvMediampPlayer,
    modifier: Modifier,
    components: OpenGLComponentProvider?,
) {
    var textureId by remember(player) { mutableIntStateOf(0) }
    var renderContextInitialized by remember(player) { mutableStateOf(false) }
    var lastContextSignature by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedSurfaceSize by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedTextureSize by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedRenderFailure by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedReadPixels by remember(player) { mutableStateOf<String?>(null) }
    var lastLoggedMpvProps by remember(player) { mutableStateOf<String?>(null) }
    val interpolator = remember(player) { FrameInterpolator() }
    val renderDebugMode = remember {
        System.getProperty("nuvio.mpv.render.debug")
            ?: System.getenv("NUVIO_MPV_RENDER_DEBUG")
            ?: ""
    }.lowercase()

    fun logSurface(message: String) {
        println("MPV_DESKTOP_SURFACE $message")
        runCatching {
            val logClass = Class.forName("com.nuvio.app.desktop.DesktopRuntimeLog")
            val logInstance = logClass.getField("INSTANCE").get(null)
            logClass.getMethod("info", String::class.java)
                .invoke(logInstance, "MPV_DESKTOP_SURFACE $message")
        }
    }

    fun releaseSkiaTextureResources() {
        player.image?.close()
        player.image = null
        player.backendTexture?.close()
        player.backendTexture = null
        textureId = 0
        player.currentSize = null
    }

    fun releaseTextureResources() {
        releaseSkiaTextureResources()
        runCatching { player.releaseTexture() }
        textureId = 0
        player.currentSize = null
    }

    fun createRenderContextIfNeeded(components: OpenGLComponentProvider): Boolean {
        if (renderContextInitialized && lastContextSignature == components.contextSignature) return true
        runCatching { components.directContext.resetGLAll() }
        val contextCreated = runCatching {
            player.createRenderContext(components.glDevice, components.glContext)
        }.getOrDefault(false)
        renderContextInitialized = contextCreated
        lastContextSignature = if (contextCreated) components.contextSignature else null
        logSurface(
            "renderContextCreate result=$contextCreated signature=${components.contextSignature} " +
                "player=${System.identityHashCode(player)}",
        )
        return contextCreated
    }

    fun recreateRenderContext(components: OpenGLComponentProvider, reason: String, surfaceSizeKey: String): Boolean {
        logSurface(
            "renderContextFullReset reason=$reason size=$surfaceSizeKey oldSignature=$lastContextSignature " +
                "newSignature=${components.contextSignature} texture=$textureId player=${System.identityHashCode(player)}",
        )
        releaseTextureResources()
        runCatching { player.releaseRenderContext() }
            .onFailure {
                logSurface(
                    "renderContextReleaseFailed reason=$reason size=$surfaceSizeKey " +
                        "error=${it::class.simpleName}:${it.message}",
                )
            }
        renderContextInitialized = false
        lastContextSignature = null
        runCatching { components.directContext.resetGLAll() }

        val contextCreated = runCatching {
            player.createRenderContext(components.glDevice, components.glContext)
        }.getOrDefault(false)
        renderContextInitialized = contextCreated
        lastContextSignature = if (contextCreated) components.contextSignature else null
        logSurface(
            "renderContextRecreate result=$contextCreated reason=$reason size=$surfaceSizeKey " +
                "signature=${components.contextSignature} player=${System.identityHashCode(player)}",
        )
        return contextCreated
    }

    DisposableEffect(components, player) {
        if (components == null) return@DisposableEffect onDispose { }

        createRenderContextIfNeeded(components)

        onDispose {
            releaseTextureResources()
            player.releaseRenderContext()
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

        if (components == null) return@Canvas
        val skiaCanvas = drawContext.canvas.nativeCanvas
        val currentContextSignature = components.contextSignature
        val targetWidth = size.width.toInt()
        val targetHeight = size.height.toInt()
        val surfaceSizeKey = "${targetWidth}x$targetHeight"

        if (!renderContextInitialized) {
            if (!createRenderContextIfNeeded(components)) return@Canvas
        }

        if (lastContextSignature != null && lastContextSignature != currentContextSignature) {
            logSurface(
                "glContextChanged old=$lastContextSignature new=$currentContextSignature " +
                    "player=${System.identityHashCode(player)}",
            )
            recreateRenderContext(components, reason = "glContextChanged", surfaceSizeKey = surfaceSizeKey)
            if (!renderContextInitialized) return@Canvas
        } else {
            lastContextSignature = currentContextSignature
        }

        if (player.currentSize == null || player.currentSize != size || textureId == 0) {
            if (targetWidth <= 0 || targetHeight <= 0) {
                if (lastLoggedSurfaceSize != surfaceSizeKey) {
                    logSurface(
                        "ignoreZeroSize size=$surfaceSizeKey currentSize=${player.currentSize} " +
                            "player=${System.identityHashCode(player)}",
                    )
                    lastLoggedSurfaceSize = surfaceSizeKey
                }
                return@Canvas
            }
            val previousSize = player.currentSize
            if (lastLoggedSurfaceSize != surfaceSizeKey) {
                logSurface(
                    "surfaceSizeChanged size=$surfaceSizeKey previous=$previousSize " +
                        "textureId=$textureId signature=$currentContextSignature " +
                        "player=${System.identityHashCode(player)}",
                )
                lastLoggedSurfaceSize = surfaceSizeKey
            }

            releaseSkiaTextureResources()
            runCatching { components.directContext.resetGLAll() }

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
                        logSurface(
                            "textureAdoptFailed size=$surfaceSizeKey texture=$newTextureId " +
                                "player=${System.identityHashCode(player)}",
                        )
                    } else {
                        textureId = newTextureId
                        player.currentSize = size
                        if (lastLoggedTextureSize != surfaceSizeKey) {
                            logSurface(
                                "textureAllocated size=$surfaceSizeKey texture=$textureId " +
                                    "signature=$currentContextSignature player=${System.identityHashCode(player)}",
                            )
                            lastLoggedTextureSize = surfaceSizeKey
                        }
                    }
                }
            } else {
                player.currentSize = null
                logSurface(
                    "textureCreateFailed size=$surfaceSizeKey signature=$currentContextSignature " +
                        "player=${System.identityHashCode(player)}",
                )
            }
        }

        if (textureId != 0) {
            val renderResult = when (renderDebugMode) {
                "solid" -> runCatching {
                    player.debugRenderSolid(0.0f, 0.85f, 0.15f, 1.0f)
                }.getOrDefault(false)

                else -> runCatching { player.renderFrame() }
                    .getOrDefault(false)
            }
            if (!renderResult) {
                val failureKey = "$surfaceSizeKey:$textureId:$currentContextSignature"
                if (lastLoggedRenderFailure != failureKey) {
                    logSurface(
                        "renderFrameFailed size=$surfaceSizeKey texture=$textureId " +
                            "signature=$currentContextSignature player=${System.identityHashCode(player)}",
                    )
                    lastLoggedRenderFailure = failureKey
                }
                return@Canvas
            }
            if (renderDebugMode == "readpixels") {
                val stats = runCatching { player.readTextureStats() }.getOrDefault("readTextureStatsFailed")
                if (lastLoggedReadPixels != stats) {
                    logSurface(
                        "readPixels stats=$stats mode=$renderDebugMode texture=$textureId " +
                            "player=${System.identityHashCode(player)}",
                    )
                    lastLoggedReadPixels = stats
                }
            }
            val props = listOf(
                "current-vo" to runCatching { player.impl.getPropertyString("current-vo") }.getOrDefault("<err>"),
                "vid" to runCatching { player.impl.getPropertyString("vid") }.getOrDefault("<err>"),
                "vo-configured" to runCatching { player.impl.getPropertyBoolean("vo-configured").toString() }.getOrDefault("<err>"),
                "video-params/w" to runCatching { player.impl.getPropertyInt("video-params/w").toString() }.getOrDefault("<err>"),
                "video-params/h" to runCatching { player.impl.getPropertyInt("video-params/h").toString() }.getOrDefault("<err>"),
                "hwdec-current" to runCatching { player.impl.getPropertyString("hwdec-current") }.getOrDefault("<err>"),
            ).joinToString(separator = " ") { (key, value) -> "$key=$value" }
            val propsLogKey = "$surfaceSizeKey:$props"
            if (lastLoggedMpvProps != propsLogKey) {
                logSurface(
                    "mpvProps size=$surfaceSizeKey texture=$textureId mode=${renderDebugMode.ifBlank { "normal" }} $props " +
                        "player=${System.identityHashCode(player)}",
                )
                lastLoggedMpvProps = propsLogKey
            }
            runCatching { components.directContext.resetGLAll() }
        }
        player.image?.let {
            skiaCanvas.drawImage(it, 0f, 0f)
        }
    }
}
