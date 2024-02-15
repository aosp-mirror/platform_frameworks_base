/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.controls.ui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.os.Trace
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.res.R
import com.android.systemui.util.boundsOnScreen
import com.android.wm.shell.taskview.TaskView
import java.util.concurrent.Executor

class PanelTaskViewController(
    private val activityContext: Context,
    private val uiExecutor: Executor,
    private val pendingIntent: PendingIntent,
    val taskView: TaskView,
    private val hide: () -> Unit = {}
) {

    init {
        taskView.alpha = 0f
    }

    private val fillInIntent =
        Intent().apply {
            // Apply flags to make behaviour match documentLaunchMode=always.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

    private val stateCallback =
        object : TaskView.Listener {
            override fun onInitialized() {
                val options =
                    ActivityOptions.makeCustomAnimation(
                        activityContext,
                        0 /* enterResId */,
                        0 /* exitResId */
                    )
                options.taskAlwaysOnTop = true

                taskView.post {
                    val roundedCorner =
                        activityContext.resources.getDimensionPixelSize(
                            R.dimen.controls_panel_corner_radius
                        )
                    val radii = FloatArray(8) { roundedCorner.toFloat() }
                    taskView.background =
                        ShapeDrawable(RoundRectShape(radii, null, null)).apply {
                            setTint(Color.TRANSPARENT)
                        }
                    taskView.clipToOutline = true
                    taskView.startActivity(
                        pendingIntent,
                        fillInIntent,
                        options,
                        taskView.boundsOnScreen
                    )
                    Trace.instant(Trace.TRACE_TAG_APP, "PanelTaskViewController - startActivity")
                }
            }

            override fun onTaskRemovalStarted(taskId: Int) {
                release()
            }

            override fun onTaskCreated(taskId: Int, name: ComponentName?) {
                taskView.alpha = 1f
            }

            override fun onBackPressedOnTaskRoot(taskId: Int) {
                hide()
            }
        }

    fun refreshBounds() {
        taskView.onLocationChanged()
    }

    /** Call when the taskView is no longer being used, shouldn't be called before removeTask. */
    @VisibleForTesting
    fun release() {
        taskView.release()
    }

    /** Call to explicitly remove the task from window manager. */
    fun removeTask() {
        taskView.removeTask()
    }

    fun launchTaskView() {
        taskView.setListener(uiExecutor, stateCallback)
    }
}
