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

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Vibrator
import android.os.VibrationEffect
import android.service.controls.Control
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.util.Log
import android.view.HapticFeedbackConstants
import com.android.systemui.globalactions.GlobalActionsComponent
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.DelayableExecutor

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControlActionCoordinatorImpl @Inject constructor(
    private val context: Context,
    private val bgExecutor: DelayableExecutor,
    private val activityStarter: ActivityStarter,
    private val keyguardStateController: KeyguardStateController,
    private val globalActionsComponent: GlobalActionsComponent
) : ControlActionCoordinator {
    private var dialog: Dialog? = null
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private var lastAction: (() -> Unit)? = null

    override fun closeDialogs() {
        dialog?.dismiss()
        dialog = null
    }

    override fun toggle(cvh: ControlViewHolder, templateId: String, isChecked: Boolean) {
        bouncerOrRun {
            val effect = if (isChecked) Vibrations.toggleOnEffect else Vibrations.toggleOffEffect
            vibrate(effect)
            cvh.action(BooleanAction(templateId, !isChecked))
        }
    }

    override fun touch(cvh: ControlViewHolder, templateId: String, control: Control) {
        vibrate(Vibrations.toggleOnEffect)

        bouncerOrRun {
            if (cvh.usePanel()) {
                showDialog(cvh, control.getAppIntent().getIntent())
            } else {
                cvh.action(CommandAction(templateId))
            }
        }
    }

    override fun drag(isEdge: Boolean) {
        bouncerOrRun {
            if (isEdge) {
                vibrate(Vibrations.rangeEdgeEffect)
            } else {
                vibrate(Vibrations.rangeMiddleEffect)
            }
        }
    }

    override fun longPress(cvh: ControlViewHolder) {
        bouncerOrRun {
            // Long press snould only be called when there is valid control state, otherwise ignore
            cvh.cws.control?.let {
                cvh.layout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showDialog(cvh, it.getAppIntent().getIntent())
            }
        }
    }

    private fun bouncerOrRun(f: () -> Unit) {
        if (!keyguardStateController.isUnlocked()) {
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            activityStarter.dismissKeyguardThenExecute({
                Log.d(ControlsUiController.TAG, "Device unlocked, invoking controls action")
                globalActionsComponent.handleShowGlobalActionsMenu()
                f()
                true
            }, null, true)
        } else {
            f()
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        bgExecutor.execute { vibrator.vibrate(effect) }
    }

    private fun showDialog(cvh: ControlViewHolder, intent: Intent) {
        dialog = DetailDialog(cvh, intent).also {
            it.setOnDismissListener { _ -> dialog = null }
            it.show()
        }
    }
}
