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
import android.app.PendingIntent
import android.content.Intent
import android.service.controls.Control
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.util.Log
import android.view.HapticFeedbackConstants
import com.android.systemui.R
import com.android.systemui.controls.controller.ControlsController

object ControlActionCoordinator {
    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 10000

    private var dialog: Dialog? = null

    fun closeDialog() {
        dialog?.dismiss()
        dialog = null
    }

    fun toggle(cvh: ControlViewHolder, templateId: String, isChecked: Boolean) {
        cvh.action(BooleanAction(templateId, !isChecked))
    }

    fun touch(cvh: ControlViewHolder, templateId: String, control: Control) {
        if (cvh.usePanel()) {
            showDialog(cvh, control.getAppIntent().getIntent())
        } else {
            cvh.action(CommandAction(templateId))
        }
    }

    /**
     * Allow apps to specify whether they would like to appear in a detail panel or within
     * the full activity by setting the {@link Control#EXTRA_USE_PANEL} flag. In order for
     * activities to determine how they are being launched, they should inspect the
     * {@link Control#EXTRA_USE_PANEL} flag for a value of true.
     */
    fun longPress(cvh: ControlViewHolder) {
        // Long press snould only be called when there is valid control state, otherwise ignore
        cvh.cws.control?.let {
            try {
                it.getAppIntent().send()
                cvh.layout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                cvh.context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (e: PendingIntent.CanceledException) {
                Log.e(ControlsUiController.TAG, "Error sending pending intent", e)
                cvh.setTransientStatus(
                    cvh.context.resources.getString(R.string.controls_error_failed))
            }
        }
    }

    private fun showDialog(cvh: ControlViewHolder, intent: Intent) {
        dialog = DetailDialog(cvh, intent).also {
            it.setOnDismissListener { _ -> dialog = null }
            it.show()
        }
    }

    fun setFocusedElement(cvh: ControlViewHolder?, controlsController: ControlsController) {
        controlsController.onFocusChanged(cvh?.cws)
    }
}
