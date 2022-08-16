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

package com.android.systemui.unfold

import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.util.WallpaperController
import javax.inject.Inject

@SysUIUnfoldScope
class UnfoldTransitionWallpaperController
@Inject
constructor(
    private val unfoldTransitionProgressProvider: UnfoldTransitionProgressProvider,
    private val wallpaperController: WallpaperController
) {

    fun init() {
        unfoldTransitionProgressProvider.addCallback(TransitionListener())
    }

    private inner class TransitionListener : TransitionProgressListener {
        override fun onTransitionProgress(progress: Float) {
            // Fully zoomed in when fully unfolded
            wallpaperController.setUnfoldTransitionZoom(1 - progress)
        }

        override fun onTransitionFinished() {
            // Resets wallpaper zoom-out to 0f when fully folded
            // When fully unfolded it is set to 0f by onTransitionProgress
            wallpaperController.setUnfoldTransitionZoom(0f)
        }
    }
}
