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
    fun loadLibraries() {
        val platform = currentPlatform()
        if (platform is Platform.Android || platform is Platform.Windows || platform is Platform.Linux) {
            System.loadLibrary("mediampv")
        }
    }
}