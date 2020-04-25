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
import android.content.Intent
import android.os.Vibrator
import android.os.VibrationEffect
import android.service.controls.Control
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.view.HapticFeedbackConstants
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.util.concurrency.DelayableExecutor

object ControlActionCoordinator {
    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 10000

    private var dialog: Dialog? = null
    private var vibrator: Vibrator? = null

    lateinit var bgExecutor: DelayableExecutor

    fun closeDialog() {
        dialog?.dismiss()
        dialog = null
    }

    /**
     * Create custom vibrations, all intended to create very subtle feedback while interacting
     * with the controls.
     */
    fun initialize(vibrator: Vibrator, bgExecutor: DelayableExecutor) {
        this.vibrator = vibrator
        this.bgExecutor = bgExecutor
    }

    fun toggle(cvh: ControlViewHolder, templateId: String, isChecked: Boolean) {
        val effect = if (isChecked) Vibrations.toggleOnEffect else Vibrations.toggleOffEffect
        vibrate(effect)
        cvh.action(BooleanAction(templateId, !isChecked))
    }

    fun touch(cvh: ControlViewHolder, templateId: String, control: Control) {
        vibrate(Vibrations.toggleOnEffect)
        if (cvh.usePanel()) {
            showDialog(cvh, control.getAppIntent().getIntent())
        } else {
            cvh.action(CommandAction(templateId))
        }
    }

    fun drag(isEdge: Boolean) {
        if (isEdge) {
            vibrate(Vibrations.rangeEdgeEffect)
        } else {
            vibrate(Vibrations.rangeMiddleEffect)
        }
    }

    /**
     * All long presses will be shown in a 3/4 height bottomsheet panel, in order for the user to
     * retain context with their favorited controls in the power menu.
     */
    fun longPress(cvh: ControlViewHolder) {
        // Long press snould only be called when there is valid control state, otherwise ignore
        cvh.cws.control?.let {
            cvh.layout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showDialog(cvh, it.getAppIntent().getIntent())
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        vibrator?.let {
            bgExecutor.execute { it.vibrate(effect) }
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
