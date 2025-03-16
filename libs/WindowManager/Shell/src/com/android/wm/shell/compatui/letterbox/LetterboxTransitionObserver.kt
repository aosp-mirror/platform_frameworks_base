/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.wm.shell.compatui.letterbox

import android.graphics.Rect
import android.os.IBinder
import android.view.SurfaceControl
import android.window.TransitionInfo
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags.appCompatRefactoring
import com.android.wm.shell.common.transition.TransitionStateHolder
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_APP_COMPAT
import com.android.wm.shell.shared.TransitionUtil.isClosingType
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions

/**
 * The [TransitionObserver] to handle Letterboxing events in Shell.
 */
class LetterboxTransitionObserver(
    shellInit: ShellInit,
    private val transitions: Transitions,
    private val letterboxController: LetterboxController,
    private val transitionStateHolder: TransitionStateHolder,
    private val letterboxModeStrategy: LetterboxControllerStrategy
) : Transitions.TransitionObserver {

    companion object {
        @JvmStatic
        private val TAG = "LetterboxTransitionObserver"
        @JvmStatic
        private val EMPTY_BOUNDS = Rect()
    }

    init {
        if (appCompatRefactoring()) {
            logV("Initializing LetterboxTransitionObserver")
            shellInit.addInitCallback({
                transitions.registerObserver(this)
            }, this)
        }
    }

    override fun onTransitionReady(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction
    ) {
        // We recognise the operation to execute and delegate to the LetterboxController
        // the related operation.
        // TODO(b/377875151): Identify Desktop Windowing Transactions.
        // TODO(b/371500295): Handle input events detection.
        for (change in info.changes) {
            change.taskInfo?.let { ti ->
                val key = LetterboxKey(ti.displayId, ti.taskId)
                val taskBounds = Rect(
                    change.endRelOffset.x,
                    change.endRelOffset.y,
                    change.endAbsBounds.width(),
                    change.endAbsBounds.height()
                )
                with(letterboxController) {
                    // TODO(b/380274087) Handle return to home from a recents transition.
                    if (isClosingType(change.mode) &&
                        !transitionStateHolder.isRecentsTransitionRunning()) {
                        // For the other types of close we need to check the recents.
                        destroyLetterboxSurface(key, finishTransaction)
                    } else {
                        val isTopActivityLetterboxed = ti.appCompatTaskInfo.isTopActivityLetterboxed
                        if (isTopActivityLetterboxed) {
                            letterboxModeStrategy.configureLetterboxMode()
                            createLetterboxSurface(
                                key,
                                startTransaction,
                                change.leash
                            )
                            val activityBounds =
                                ti.appCompatTaskInfo.topActivityLetterboxBounds ?: EMPTY_BOUNDS
                            updateLetterboxSurfaceBounds(
                                key,
                                startTransaction,
                                taskBounds,
                                activityBounds
                            )
                        }
                        updateLetterboxSurfaceVisibility(
                            key,
                            startTransaction,
                            isTopActivityLetterboxed
                        )
                    }
                    dump()
                }
            }
        }
    }

    private fun logV(msg: String) {
        ProtoLog.v(WM_SHELL_APP_COMPAT, "%s: %s", TAG, msg)
    }
}
