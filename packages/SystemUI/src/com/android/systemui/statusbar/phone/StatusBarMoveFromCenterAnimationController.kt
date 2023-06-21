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
import com.android.systemui.shared.animation.UnfoldMoveFromCenterAnimator.AlphaProvider
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController.StatusBarViewsCenterProvider
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.util.CurrentActivityTypeProvider
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import javax.inject.Inject
import kotlin.math.max

@SysUIUnfoldScope
class StatusBarMoveFromCenterAnimationController @Inject constructor(
    private val progressProvider: ScopedUnfoldTransitionProgressProvider,
    private val currentActivityTypeProvider: CurrentActivityTypeProvider,
    windowManager: WindowManager
) {

    // Whether we're on home activity. Updated only when the animation starts.
    private var isOnHomeActivity: Boolean? = null

    private val transitionListener = TransitionListener()
    private val moveFromCenterAnimator = UnfoldMoveFromCenterAnimator(
        windowManager,
        viewCenterProvider = StatusBarViewsCenterProvider(),
        alphaProvider = StatusBarIconsAlphaProvider()
    )

    fun onViewsReady(viewsToAnimate: Array<View>) {
        moveFromCenterAnimator.updateDisplayProperties()

        viewsToAnimate.forEach {
            moveFromCenterAnimator.registerViewForAnimation(it)
        }

        progressProvider.addCallback(transitionListener)
    }

    fun onViewDetached() {
        progressProvider.removeCallback(transitionListener)
        moveFromCenterAnimator.clearRegisteredViews()
    }

    fun onStatusBarWidthChanged() {
        moveFromCenterAnimator.updateDisplayProperties()
        moveFromCenterAnimator.updateViewPositions()
    }

    private inner class TransitionListener : TransitionProgressListener {
        override fun onTransitionStarted() {
            isOnHomeActivity = currentActivityTypeProvider.isHomeActivity
        }

        override fun onTransitionProgress(progress: Float) {
            moveFromCenterAnimator.onTransitionProgress(progress)
        }

        override fun onTransitionFinished() {
            // Reset translations when transition is stopped/cancelled
            // (e.g. the transition could be cancelled mid-way when rotating the screen)
            moveFromCenterAnimator.onTransitionProgress(1f)
            isOnHomeActivity = null
        }
    }


    /**
     * In certain cases, an alpha is applied based on the progress.
     *
     * This mainly happens to hide the statusbar during the unfold animation while on apps, as the
     * bounds of the app "collapse" to the center, but the statusbar doesn't.
     * While on launcher, this alpha is not applied.
     */
    private inner class StatusBarIconsAlphaProvider : AlphaProvider {
        override fun getAlpha(progress: Float): Float {
            if (isOnHomeActivity == true) {
                return 1.0f
            }
            return max(
                0f,
                (progress - ICONS_START_APPEARING_PROGRESS) / (1 - ICONS_START_APPEARING_PROGRESS)
            )
        }
    }
}

private const val ICONS_START_APPEARING_PROGRESS = 0.75F
