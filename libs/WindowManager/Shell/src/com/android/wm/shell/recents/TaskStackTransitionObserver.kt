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

package com.android.wm.shell.recents

import android.app.ActivityManager.RunningTaskInfo
import android.os.IBinder
import android.util.ArrayMap
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.DesktopModeFlags
import android.window.TransitionInfo
import com.android.wm.shell.shared.TransitionUtil
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import dagger.Lazy
import java.util.concurrent.Executor

/**
 * A [Transitions.TransitionObserver] that observes shell transitions and sends updates to listeners
 * about task stack changes.
 *
 * TODO(346588978) Move split/pip signals here as well so that launcher don't need to handle it
 */
class TaskStackTransitionObserver(
    private val transitions: Lazy<Transitions>,
    shellInit: ShellInit
) : Transitions.TransitionObserver {
    private val taskStackTransitionObserverListeners =
        ArrayMap<TaskStackTransitionObserverListener, Executor>()

    init {
        shellInit.addInitCallback(::onInit, this)
    }

    fun onInit() {
        transitions.get().registerObserver(this)
    }

    override fun onTransitionReady(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction
    ) {
        if (DesktopModeFlags.ENABLE_TASK_STACK_OBSERVER_IN_SHELL.isTrue) {
            for (change in info.changes) {
                if (change.flags and TransitionInfo.FLAG_IS_WALLPAPER != 0) {
                    continue
                }

                val taskInfo = change.taskInfo
                if (taskInfo == null || taskInfo.taskId == -1) {
                    continue
                }

                // Find the first task that is opening, this should be the one at the front after
                // the transition
                if (TransitionUtil.isOpeningType(change.mode)) {
                    notifyOnTaskMovedToFront(taskInfo)
                    break
                } else if (change.mode == TRANSIT_CHANGE) {
                    notifyOnTaskChanged(taskInfo)
                }
            }
        }
    }

    override fun onTransitionStarting(transition: IBinder) {}

    override fun onTransitionMerged(merged: IBinder, playing: IBinder) {}

    override fun onTransitionFinished(transition: IBinder, aborted: Boolean) {}

    fun addTaskStackTransitionObserverListener(
        taskStackTransitionObserverListener: TaskStackTransitionObserverListener,
        executor: Executor
    ) {
        taskStackTransitionObserverListeners[taskStackTransitionObserverListener] = executor
    }

    fun removeTaskStackTransitionObserverListener(
        taskStackTransitionObserverListener: TaskStackTransitionObserverListener
    ) {
        taskStackTransitionObserverListeners.remove(taskStackTransitionObserverListener)
    }

    private fun notifyOnTaskMovedToFront(taskInfo: RunningTaskInfo) {
        taskStackTransitionObserverListeners.forEach { (listener, executor) ->
            executor.execute { listener.onTaskMovedToFrontThroughTransition(taskInfo) }
        }
    }

    private fun notifyOnTaskChanged(taskInfo: RunningTaskInfo) {
        taskStackTransitionObserverListeners.forEach { (listener, executor) ->
            executor.execute { listener.onTaskChangedThroughTransition(taskInfo) }
        }
    }

    /** Listener to use to get updates regarding task stack from this observer */
    interface TaskStackTransitionObserverListener {
        /** Called when a task is moved to front. */
        fun onTaskMovedToFrontThroughTransition(taskInfo: RunningTaskInfo) {}
        /** Called when a task info has changed. */
        fun onTaskChangedThroughTransition(taskInfo: RunningTaskInfo) {}
    }
}
