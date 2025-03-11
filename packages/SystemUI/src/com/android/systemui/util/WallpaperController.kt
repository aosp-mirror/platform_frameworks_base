/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util

import android.app.WallpaperManager
import android.util.Log
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.wallpapers.data.repository.WallpaperRepository
import javax.inject.Inject
import kotlin.math.max

private const val TAG = "WallpaperController"

/**
 * Controller for wallpaper-related logic.
 *
 * Note: New logic should be added to [WallpaperRepository], not this class.
 */
@SysUISingleton
class WallpaperController
@Inject
constructor(
    private val wallpaperManager: WallpaperManager,
    private val wallpaperRepository: WallpaperRepository,
) {

    var rootView: View? = null
        set(value) {
            field = value
            wallpaperRepository.rootView = value
        }

    private var notificationShadeZoomOut: Float = 0f
    private var unfoldTransitionZoomOut: Float = 0f

    private val shouldUseDefaultUnfoldTransition: Boolean
        get() = wallpaperRepository.wallpaperInfo.value?.shouldUseDefaultUnfoldTransition() ?: true

    fun setNotificationShadeZoom(zoomOut: Float) {
        notificationShadeZoomOut = zoomOut
        updateZoom()
    }

    fun setUnfoldTransitionZoom(zoomOut: Float) {
        if (shouldUseDefaultUnfoldTransition) {
            unfoldTransitionZoomOut = zoomOut
            updateZoom()
        }
    }

    private fun updateZoom() {
        setWallpaperZoom(max(notificationShadeZoomOut, unfoldTransitionZoomOut))
    }

    private fun setWallpaperZoom(zoomOut: Float) {
        try {
            rootView?.let { root ->
                if (root.isAttachedToWindow && root.windowToken != null) {
                    wallpaperManager.setWallpaperZoomOut(root.windowToken, zoomOut)
                } else {
                    Log.i(TAG, "Won't set zoom. Window not attached $root")
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Can't set zoom. Window is gone: ${rootView?.windowToken}", e)
        }
    }
}
