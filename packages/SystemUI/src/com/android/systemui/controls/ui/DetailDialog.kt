/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.app.Dialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsets.Type
import android.view.WindowManager
import android.widget.ImageView
import com.android.internal.policy.ScreenDecorationsUtils
import com.android.systemui.R
import com.android.wm.shell.TaskView

/**
 * A dialog that provides an {@link TaskView}, allowing the application to provide
 * additional information and actions pertaining to a {@link android.service.controls.Control}.
 * The activity being launched is specified by {@link android.service.controls.Control#getAppIntent}.
 */
class DetailDialog(
    val activityContext: Context?,
    val taskView: TaskView,
    val intent: Intent,
    val cvh: ControlViewHolder
) : Dialog(
    activityContext ?: cvh.context,
    R.style.Theme_SystemUI_Dialog_Control_DetailPanel
) {
    companion object {
        /*
         * Indicate to the activity that it is being rendered in a bottomsheet, and they
         * should optimize the layout for a smaller space.
         */
        private const val EXTRA_USE_PANEL = "controls.DISPLAY_IN_PANEL"
    }

    var detailTaskId = INVALID_TASK_ID

    fun removeDetailTask() {
        if (detailTaskId == INVALID_TASK_ID) return
        ActivityTaskManager.getInstance().removeTask(detailTaskId)
        detailTaskId = INVALID_TASK_ID
    }

    val stateCallback = object : TaskView.Listener {
        override fun onInitialized() {
            val launchIntent = Intent(intent)
            launchIntent.putExtra(EXTRA_USE_PANEL, true)

            // Apply flags to make behaviour match documentLaunchMode=always.
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

            val options = activityContext?.let {
                ActivityOptions.makeCustomAnimation(
                    it,
                    0 /* enterResId */,
                    0 /* exitResId */
                )
            } ?: ActivityOptions.makeBasic()
            taskView.startActivity(
                PendingIntent.getActivity(context, 0, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                null /* fillInIntent */,
                options,
                getTaskViewBounds()
            )
        }

        override fun onTaskRemovalStarted(taskId: Int) {
            detailTaskId = INVALID_TASK_ID
            dismiss()
        }

        override fun onTaskCreated(taskId: Int, name: ComponentName?) {
            detailTaskId = taskId
        }

        override fun onReleased() {
            removeDetailTask()
        }
    }

    init {
        if (activityContext == null) {
            window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
        }

        // To pass touches to the task inside TaskView.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)

        setContentView(R.layout.controls_detail_dialog)

        requireViewById<ViewGroup>(R.id.controls_activity_view).apply {
            addView(taskView)
        }

        requireViewById<ImageView>(R.id.control_detail_close).apply {
            setOnClickListener { _: View -> dismiss() }
        }

        requireViewById<ImageView>(R.id.control_detail_open_in_app).apply {
            setOnClickListener { v: View ->
                // Remove the task explicitly, since onRelease() callback will be executed after
                // startActivity() below is called.
                removeDetailTask()
                dismiss()
                context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                v.context.startActivity(intent)
            }
        }

        // consume all insets to achieve slide under effect
        window.getDecorView().setOnApplyWindowInsetsListener {
            v: View, insets: WindowInsets ->
                taskView.apply {
                    val l = getPaddingLeft()
                    val t = getPaddingTop()
                    val r = getPaddingRight()
                    setPadding(l, t, r, insets.getInsets(Type.systemBars()).bottom)
                }

                val l = v.getPaddingLeft()
                val b = v.getPaddingBottom()
                val r = v.getPaddingRight()
                v.setPadding(l, insets.getInsets(Type.systemBars()).top, r, b)

                WindowInsets.CONSUMED
        }

        if (ScreenDecorationsUtils.supportsRoundedCornersOnWindows(context.getResources())) {
            val cornerRadius = context.resources
                .getDimensionPixelSize(R.dimen.controls_activity_view_corner_radius)
            taskView.setCornerRadius(cornerRadius.toFloat())
        }

        taskView.setListener(cvh.uiExecutor, stateCallback)
    }

    fun getTaskViewBounds(): Rect {
        val wm = context.getSystemService(WindowManager::class.java)
        val windowMetrics = wm.getCurrentWindowMetrics()
        val rect = windowMetrics.bounds
        val metricInsets = windowMetrics.windowInsets
        val insets = metricInsets.getInsetsIgnoringVisibility(Type.systemBars()
                or Type.displayCutout())
        val headerHeight = context.resources.getDimensionPixelSize(
                R.dimen.controls_detail_dialog_header_height)

        val finalRect = Rect(rect.left - insets.left /* left */,
                rect.top + insets.top + headerHeight /* top */,
                rect.right - insets.right /* right */,
                rect.bottom - insets.bottom /* bottom */)
        return finalRect
    }

    override fun dismiss() {
        if (!isShowing()) return
        taskView.release()

        super.dismiss()
    }
}
