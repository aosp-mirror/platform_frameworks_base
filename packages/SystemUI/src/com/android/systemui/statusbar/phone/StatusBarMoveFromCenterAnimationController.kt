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
package com.android.systemui.statusbar.phone

import android.view.View
import android.view.WindowManager
import com.android.systemui.shared.animation.UnfoldMoveFromCenterAnimator
import com.android.systemui.shared.animation.UnfoldMoveFromCenterAnimator.ViewCenterProvider
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import javax.inject.Inject

@SysUIUnfoldScope
class StatusBarMoveFromCenterAnimationController @Inject constructor(
    private val progressProvider: ScopedUnfoldTransitionProgressProvider,
    private val windowManager: WindowManager
) {

    private val transitionListener = TransitionListener()
    private var moveFromCenterAnimator: UnfoldMoveFromCenterAnimator? = null

    fun onViewsReady(viewsToAnimate: Array<View>, viewCenterProvider: ViewCenterProvider) {
        moveFromCenterAnimator = UnfoldMoveFromCenterAnimator(windowManager,
            viewCenterProvider = viewCenterProvider)

        moveFromCenterAnimator?.updateDisplayProperties()

        viewsToAnimate.forEach {
            moveFromCenterAnimator?.registerViewForAnimation(it)
        }

        progressProvider.addCallback(transitionListener)
    }

    fun onViewDetached() {
        progressProvider.removeCallback(transitionListener)
        moveFromCenterAnimator?.clearRegisteredViews()
        moveFromCenterAnimator = null
    }

    fun onStatusBarWidthChanged() {
        moveFromCenterAnimator?.updateDisplayProperties()
        moveFromCenterAnimator?.updateViewPositions()
    }

    private inner class TransitionListener : TransitionProgressListener {
        override fun onTransitionProgress(progress: Float) {
            moveFromCenterAnimator?.onTransitionProgress(progress)
        }

        override fun onTransitionFinished() {
            // Reset translations when transition is stopped/cancelled
            // (e.g. the transition could be cancelled mid-way when rotating the screen)
            moveFromCenterAnimator?.onTransitionProgress(1f)
        }
    }
}
