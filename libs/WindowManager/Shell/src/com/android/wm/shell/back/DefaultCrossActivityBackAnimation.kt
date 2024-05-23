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
package com.android.wm.shell.back

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceControl
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.animation.Interpolators
import com.android.wm.shell.shared.annotations.ShellMainThread
import javax.inject.Inject
import kotlin.math.max

/** Class that defines cross-activity animation. */
@ShellMainThread
class DefaultCrossActivityBackAnimation
@Inject
constructor(
    context: Context,
    background: BackAnimationBackground,
    rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
) :
    CrossActivityBackAnimation(
        context,
        background,
        rootTaskDisplayAreaOrganizer,
        SurfaceControl.Transaction(),
        Choreographer.getInstance()
    ) {

    private val postCommitInterpolator = Interpolators.FAST_OUT_SLOW_IN
    private val enteringStartOffset =
        context.resources.getDimension(R.dimen.cross_activity_back_entering_start_offset)
    override val allowEnteringYShift = true

    override fun preparePreCommitEnteringRectMovement() {
        // the entering target starts 96dp to the left of the screen edge...
        startEnteringRect.set(startClosingRect)
        startEnteringRect.offset(-enteringStartOffset, 0f)
        // ...and gets scaled in sync with the closing target
        targetEnteringRect.set(startEnteringRect)
        targetEnteringRect.scaleCentered(MAX_SCALE)
    }

    override fun onGestureCommitted(velocity: Float) {
        // We enter phase 2 of the animation, the starting coordinates for phase 2 are the current
        // coordinate of the gesture driven phase. Let's update the start and target rects and kick
        // off the animator in the superclass
        startClosingRect.set(currentClosingRect)
        startEnteringRect.set(currentEnteringRect)
        targetEnteringRect.set(backAnimRect)
        targetClosingRect.set(backAnimRect)
        targetClosingRect.offset(currentClosingRect.left + enteringStartOffset, 0f)
        super.onGestureCommitted(velocity)
    }

    override fun onPostCommitProgress(linearProgress: Float) {
        super.onPostCommitProgress(linearProgress)
        val closingAlpha = max(1f - linearProgress * 2, 0f)
        val progress = postCommitInterpolator.getInterpolation(linearProgress)
        currentClosingRect.setInterpolatedRectF(startClosingRect, targetClosingRect, progress)
        applyTransform(closingTarget?.leash, currentClosingRect, closingAlpha)
        currentEnteringRect.setInterpolatedRectF(startEnteringRect, targetEnteringRect, progress)
        applyTransform(enteringTarget?.leash, currentEnteringRect, 1f)
        applyTransaction()
    }
}
