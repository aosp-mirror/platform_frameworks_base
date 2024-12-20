/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.desktopmode.desktopwallpaperactivity

import android.util.SparseArray
import android.view.Display.DEFAULT_DISPLAY
import android.window.WindowContainerToken

/** Provides per display window container tokens for [DesktopWallpaperActivity]. */
class DesktopWallpaperActivityTokenProvider {

    private val wallpaperActivityTokenByDisplayId = SparseArray<WindowContainerToken>()

    fun setToken(token: WindowContainerToken, displayId: Int = DEFAULT_DISPLAY) {
        wallpaperActivityTokenByDisplayId[displayId] = token
    }

    fun getToken(displayId: Int = DEFAULT_DISPLAY): WindowContainerToken? {
        return wallpaperActivityTokenByDisplayId[displayId]
    }

    fun removeToken(displayId: Int = DEFAULT_DISPLAY) {
        wallpaperActivityTokenByDisplayId.delete(displayId)
    }
}
