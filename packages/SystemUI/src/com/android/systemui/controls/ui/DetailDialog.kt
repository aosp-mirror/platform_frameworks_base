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

import android.app.Activity
import android.app.ActivityOptions
import android.app.ComponentOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
import android.app.Dialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsets.Type
import android.view.WindowManager
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import com.android.internal.policy.ScreenDecorationsUtils
import com.android.systemui.res.R
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.boundsOnScreen
import com.android.wm.shell.taskview.TaskView

/**
 * A dialog that provides an {@link TaskView}, allowing the application to provide
 * additional information and actions pertaining to a {@link android.service.controls.Control}.
 * The activity being launched is specified by {@link android.service.controls.Control#getAppIntent}.
 */
class DetailDialog(
        val activityContext: Context,
        val broadcastSender: BroadcastSender,
        val taskView: TaskView,
        val pendingIntent: PendingIntent,
        val cvh: ControlViewHolder,
        val keyguardStateController: KeyguardStateController,
        val activityStarter: ActivityStarter
) : Dialog(
    activityContext,
    R.style.Theme_SystemUI_Dialog_Control_DetailPanel
) {
    companion object {
        /*
         * Indicate to the activity that it is being rendered in a bottomsheet, and they
         * should optimize the layout for a smaller space.
         */
        private const val EXTRA_USE_PANEL = "controls.DISPLAY_IN_PANEL"
    }

    private lateinit var taskViewContainer: View
    private lateinit var controlDetailRoot: View
    private val taskWidthPercentWidth = activityContext.resources.getFloat(
        R.dimen.controls_task_view_width_percentage
    )

    private val fillInIntent = Intent().apply {
        putExtra(EXTRA_USE_PANEL, true)

        // Apply flags to make behaviour match documentLaunchMode=always.
        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

    @VisibleForTesting
    val stateCallback = object : TaskView.Listener {
        override fun onInitialized() {
            taskViewContainer.apply {
                // For some devices, limit the overall width of the taskView
                val lp = getLayoutParams()
                lp.width = (getWidth() * taskWidthPercentWidth).toInt()
                setLayoutParams(lp)
            }

            val options = ActivityOptions.makeCustomAnimation(
                activityContext,
                0 /* enterResId */,
                0 /* exitResId */
            ).apply {
                pendingIntentBackgroundActivityStartMode = MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                isPendingIntentBackgroundActivityLaunchAllowedByPermission = true
                taskAlwaysOnTop = true
            }

            taskView.startActivity(
                pendingIntent,
                fillInIntent,
                options,
                taskView.boundsOnScreen,
            )
        }

        override fun onTaskRemovalStarted(taskId: Int) {
            taskView.release()
        }

        override fun onTaskCreated(taskId: Int, name: ComponentName?) {
            requireViewById<ViewGroup>(R.id.controls_activity_view).apply {
                setAlpha(1f)
            }
        }
        override fun onBackPressedOnTaskRoot(taskId: Int) {
            dismiss()
        }
    }

    init {
        // To pass touches to the task inside TaskView.
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window?.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)

        setContentView(R.layout.controls_detail_dialog)

        taskViewContainer = requireViewById<ViewGroup>(R.id.control_task_view_container)
        controlDetailRoot = requireViewById<View>(R.id.control_detail_root).apply {
            setOnClickListener { _: View -> dismiss() }
        }

        requireViewById<ViewGroup>(R.id.controls_activity_view).apply {
            addView(taskView)
            setAlpha(0f)
        }

        requireViewById<ImageView>(R.id.control_detail_close).apply {
            setOnClickListener { _: View -> dismiss() }
        }

        requireViewById<ImageView>(R.id.control_detail_open_in_app).apply {
            setOnClickListener { v: View ->
                dismiss()

                val action = ActivityStarter.OnDismissAction {
                    // Remove the task explicitly, since onRelease() callback will be executed after
                    // startActivity() below is called.
                    broadcastSender.closeSystemDialogs()
                    // not sent as interactive, lest the higher-importance activity launch
                    // be impacted
                    val options = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                            .toBundle()
                    pendingIntent.send(options)
                    false
                }
                if (keyguardStateController.isUnlocked()) {
                    action.onDismiss()
                } else {
                    activityStarter.dismissKeyguardThenExecute(
                        action,
                        null /* cancel */,
                        true /* afterKeyguardGone */
                    )
                }
            }
        }

        // consume all insets to achieve slide under effect
        checkNotNull(window).decorView.setOnApplyWindowInsetsListener {
            v: View, insets: WindowInsets ->
                val l = v.getPaddingLeft()
                val r = v.getPaddingRight()
                val insets = insets.getInsets(Type.systemBars())
                v.setPadding(l, insets.top, r, insets.bottom)

                WindowInsets.CONSUMED
        }

        if (ScreenDecorationsUtils.supportsRoundedCornersOnWindows(context.getResources())) {
            val cornerRadius = context.resources
                .getDimensionPixelSize(R.dimen.controls_activity_view_corner_radius)
            taskView.setCornerRadius(cornerRadius.toFloat())
        }

        taskView.setListener(cvh.uiExecutor, stateCallback)
    }

    override fun dismiss() {
        if (!isShowing()) return
        taskView.removeTask()

        val isActivityFinishing =
            (activityContext as? Activity)?.let { it.isFinishing || it.isDestroyed }
        if (isActivityFinishing == true) {
            // Don't dismiss the dialog if the activity is finishing, it will get removed
            return
        }
        super.dismiss()
    }
}
