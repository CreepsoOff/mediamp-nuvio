/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

interface EventListener {
    fun onPropertyChange(name: String)
    fun onPropertyChange(name: String, value: Boolean)
    fun onPropertyChange(name: String, value: Long)
    fun onPropertyChange(name: String, value: Double)
    fun onPropertyChange(name: String, value: String)
    fun onFileLoaded()
    fun onVideoReconfig()
    fun onEndFile(reason: Int)
    fun onTracksChanged()
}
