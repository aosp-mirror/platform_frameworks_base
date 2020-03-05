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

import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.util.Log
import android.view.HapticFeedbackConstants

object ControlActionCoordinator {
    public const val MIN_LEVEL = 0
    public const val MAX_LEVEL = 10000

    private var useDetailDialog: Boolean? = null

    fun toggle(cvh: ControlViewHolder, templateId: String, isChecked: Boolean) {
        cvh.action(BooleanAction(templateId, !isChecked))

        val nextLevel = if (isChecked) MIN_LEVEL else MAX_LEVEL
        cvh.clipLayer.setLevel(nextLevel)
    }

    fun touch(cvh: ControlViewHolder, templateId: String) {
        cvh.action(CommandAction(templateId))
    }

    fun longPress(cvh: ControlViewHolder) {
        // Long press snould only be called when there is valid control state, otherwise ignore
        cvh.cws.control?.let {
            if (useDetailDialog == null) {
                useDetailDialog = Settings.Secure.getInt(cvh.context.getContentResolver(),
                    "systemui.controls_use_detail_panel", 0) != 0
            }

            try {
                cvh.layout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (useDetailDialog!!) {
                    DetailDialog(cvh.context, it.getAppIntent()).show()
                } else {
                    it.getAppIntent().send()
                    val closeDialog = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                    cvh.context.sendBroadcast(closeDialog)
                }
            } catch (e: PendingIntent.CanceledException) {
                Log.e(ControlsUiController.TAG, "Error sending pending intent", e)
                cvh.setTransientStatus("Error opening application")
            }
        }
    }
}
