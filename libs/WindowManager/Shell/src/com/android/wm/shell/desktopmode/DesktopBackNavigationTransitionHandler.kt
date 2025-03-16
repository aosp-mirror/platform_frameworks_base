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
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.SurfaceControl.Transaction
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.shared.TransitionUtil
import com.android.wm.shell.shared.animation.MinimizeAnimator.create
import com.android.wm.shell.transition.Transitions

/**
 * The [Transitions.TransitionHandler] that handles transitions for tasks that are closing or going
 * to back as part of back navigation. This handler is used only for animating transitions.
 */
class DesktopBackNavigationTransitionHandler(
    private val mainExecutor: ShellExecutor,
    private val animExecutor: ShellExecutor,
    private val displayController: DisplayController,
) : Transitions.TransitionHandler {

    /** Shouldn't handle anything */
    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo,
    ): WindowContainerTransaction? = null

    /** Animates a transition with minimizing tasks */
    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: Transaction,
        finishTransaction: Transaction,
        finishCallback: Transitions.TransitionFinishCallback,
    ): Boolean {
        if (!TransitionUtil.isClosingType(info.type)) return false

        val animations = mutableListOf<Animator>()
        val onAnimFinish: (Animator) -> Unit = { animator ->
            mainExecutor.execute {
                // Animation completed
                animations.remove(animator)
                if (animations.isEmpty()) {
                    // All animations completed, finish the transition
                    finishCallback.onTransitionFinished(/* wct= */ null)
                }
            }
        }

        animations +=
            info.changes
                .filter {
                    it.mode == info.type && it.taskInfo?.windowingMode == WINDOWING_MODE_FREEFORM
                }
                .mapNotNull { createMinimizeAnimation(it, finishTransaction, onAnimFinish) }
        if (animations.isEmpty()) return false
        animExecutor.execute { animations.forEach(Animator::start) }
        return true
    }

    private fun createMinimizeAnimation(
        change: TransitionInfo.Change,
        finishTransaction: Transaction,
        onAnimFinish: (Animator) -> Unit,
    ): Animator? {
        val t = Transaction()
        val sc = change.leash
        finishTransaction.hide(sc)
        val displayMetrics: DisplayMetrics? =
            change.taskInfo?.let {
                displayController.getDisplayContext(it.displayId)?.getResources()?.displayMetrics
            }
        return displayMetrics?.let { create(it, change, t, onAnimFinish) }
    }
}
