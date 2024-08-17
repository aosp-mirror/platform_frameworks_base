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
package com.android.wm.shell.desktopmode

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.IBinder
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_OPEN
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback

/**
 * Transition handler for drag-and-drop (i.e., tab tear) transitions that occur in desktop mode.
 */
class DesktopModeDragAndDropTransitionHandler(private val transitions: Transitions) :
    Transitions.TransitionHandler {
    private val pendingTransitionTokens: MutableList<IBinder> = mutableListOf()

    /**
     * Begin a transition when a [android.app.PendingIntent] is dropped without a window to
     * accept it.
     */
    fun handleDropEvent(wct: WindowContainerTransaction): IBinder {
        val token = transitions.startTransition(TRANSIT_OPEN, wct, this)
        pendingTransitionTokens.add(token)
        return token
    }

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: TransitionFinishCallback
    ): Boolean {
        if (!pendingTransitionTokens.contains(transition)) return false
        val change = findRelevantChange(info)
        val leash = change.leash
        val endBounds = change.endAbsBounds
        startTransaction.hide(leash)
            .setWindowCrop(leash, endBounds.width(), endBounds.height())
            .apply()
        val animator = ValueAnimator()
        animator.setFloatValues(0f, 1f)
        animator.setDuration(FADE_IN_ANIMATION_DURATION)
        val t = SurfaceControl.Transaction()
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                t.show(leash)
                t.apply()
            }

            override fun onAnimationEnd(animation: Animator) {
                finishCallback.onTransitionFinished(null)
            }
        })
        animator.addUpdateListener { animation: ValueAnimator ->
            t.setAlpha(leash, animation.animatedFraction)
            t.apply()
        }
        animator.start()
        pendingTransitionTokens.remove(transition)
        return true
    }

    private fun findRelevantChange(info: TransitionInfo): TransitionInfo.Change {
        val matchingChanges =
            info.changes.filter { c ->
                isValidTaskChange(c) && c.mode == TRANSIT_OPEN
            }
        if (matchingChanges.size != 1) {
            throw IllegalStateException(
                "Expected 1 relevant change but found: ${matchingChanges.size}"
            )
        }
        return matchingChanges.first()
    }

    private fun isValidTaskChange(change: TransitionInfo.Change): Boolean {
        return change.taskInfo != null && change.taskInfo?.taskId != -1
    }

    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo
    ): WindowContainerTransaction? {
        return null
    }

    companion object {
        const val FADE_IN_ANIMATION_DURATION = 300L
    }
}
