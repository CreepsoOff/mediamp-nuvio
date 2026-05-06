/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform

object LibraryLoader {
    @Volatile
    private var loaded = false

    fun loadLibraries() {
        if (loaded) return
        val platform = currentPlatform()
        if (platform is Platform.Android || platform is Platform.Windows || platform is Platform.Linux) {
            try {
                System.loadLibrary("mediampv")
                loaded = true
            } catch (_: UnsatisfiedLinkError) {
                // Library may have already been loaded via System.load() by the runtime bootstrap
                loaded = true
            }
        }
    }
}