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

package com.android.systemui.mediaprojection.appselector.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.UiThread
import android.graphics.Rect
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.SurfaceControl
import android.view.animation.DecelerateInterpolator
import android.window.IRemoteTransitionFinishedCallback
import android.window.RemoteTransitionStub
import android.window.TransitionInfo
import android.window.WindowContainerToken
import com.android.app.viewcapture.ViewCapture
import com.android.internal.policy.TransitionAnimation
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorActivity.Companion.TAG

class RemoteRecentSplitTaskTransitionRunner(
    private val firstTaskId: Int,
    private val secondTaskId: Int,
    private val viewPosition: IntArray,
    private val screenBounds: Rect,
    private val handleResult: () -> Unit,
) : RemoteTransitionStub() {
    override fun startAnimation(
        transition: IBinder?,
        info: TransitionInfo?,
        t: SurfaceControl.Transaction?,
        finishedCallback: IRemoteTransitionFinishedCallback
    ) {
        val launchAnimation = AnimatorSet()
        var rootCandidate =
            info!!.changes.firstOrNull {
                it.taskInfo?.taskId == firstTaskId || it.taskInfo?.taskId == secondTaskId
            }

        // If we could not find a proper root candidate, something went wrong.
        check(rootCandidate != null) { "Could not find a split root candidate" }

        // Recurse up the tree until parent is null, then we've found our root.
        var parentToken: WindowContainerToken? = rootCandidate.parent
        while (parentToken != null) {
            rootCandidate = info.getChange(parentToken) ?: break
            parentToken = rootCandidate.parent
        }

        // Make sure nothing weird happened, like getChange() returning null.
        check(rootCandidate != null) { "Failed to find a root leash" }

        // Ending position is the full device screen.
        val startingScale = 0.25f

        val startX = viewPosition[0]
        val startY = viewPosition[1]
        val endX = screenBounds.left
        val endY = screenBounds.top

        ViewCapture.MAIN_EXECUTOR.execute {
            val progressUpdater = ValueAnimator.ofFloat(0f, 1f)
            with(progressUpdater) {
                interpolator = DecelerateInterpolator(1.5f)
                setDuration(TransitionAnimation.DEFAULT_APP_TRANSITION_DURATION.toLong())

                addUpdateListener { valueAnimator ->
                    val progress = valueAnimator.animatedFraction

                    val x = startX + ((endX - startX) * progress)
                    val y = startY + ((endY - startY) * progress)
                    val scale = startingScale + ((1 - startingScale) * progress)

                    t!!
                        .setPosition(rootCandidate.leash, x, y)
                        .setScale(rootCandidate.leash, scale, scale)
                        .setAlpha(rootCandidate.leash, progress)
                        .apply()
                }

                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            try {
                                onTransitionFinished()
                                finishedCallback.onTransitionFinished(null, null)
                            } catch (e: RemoteException) {
                                Log.e(TAG, "Failed to call transition finished callback", e)
                            }
                        }
                    }
                )
            }

            launchAnimation.play(progressUpdater)
            launchAnimation.start()
        }
    }

    @Throws(RemoteException::class)
    override fun onTransitionConsumed(transition: IBinder, aborted: Boolean) {
        Log.w(TAG, "unexpected consumption of app selector transition: aborted=$aborted")
    }

    @UiThread
    private fun onTransitionFinished() {
        // After finished transition, then invoke callback to close the app selector, so that
        // finish animation of app selector does not override the launch animation of the split
        // tasks
        handleResult()
    }
}
