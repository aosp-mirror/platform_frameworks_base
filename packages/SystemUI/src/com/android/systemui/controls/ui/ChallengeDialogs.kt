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

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.actions.ModeAction
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText

import com.android.systemui.R

/**
 * Creates all dialogs for challengeValues that can occur from a call to
 * [ControlsProviderService#performControlAction]. The types of challenge responses are listed in
 * [ControlAction.ResponseResult].
 */
object ChallengeDialogs {

    private const val WINDOW_TYPE = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
    private const val STYLE = android.R.style.Theme_DeviceDefault_Dialog_Alert

    /**
     * AlertDialogs to handle [ControlAction#RESPONSE_CHALLENGE_PIN] and
     * [ControlAction#RESPONSE_CHALLENGE_PIN] responses, decided by the useAlphaNumeric
     * parameter.
     */
    fun createPinDialog(
        cvh: ControlViewHolder,
        useAlphaNumeric: Boolean,
        useRetryStrings: Boolean,
        onCancel: () -> Unit
    ): Dialog? {
        val lastAction = cvh.lastAction
        if (lastAction == null) {
            Log.e(ControlsUiController.TAG,
                "PIN Dialog attempted but no last action is set. Will not show")
            return null
        }
        val res = cvh.context.resources
        val (title, instructions) = if (useRetryStrings) {
            Pair(
                res.getString(R.string.controls_pin_wrong),
                R.string.controls_pin_instructions_retry
            )
        } else {
            Pair(
                res.getString(R.string.controls_pin_verify, cvh.title.getText()),
                R.string.controls_pin_instructions
            )
        }
        val builder = AlertDialog.Builder(cvh.context, STYLE).apply {
            setTitle(title)
            setView(R.layout.controls_dialog_pin)
            setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { dialog, _ ->
                    if (dialog is Dialog) {
                        dialog.requireViewById<EditText>(R.id.controls_pin_input)
                        val pin = dialog.requireViewById<EditText>(R.id.controls_pin_input)
                            .getText().toString()
                        cvh.action(addChallengeValue(lastAction, pin))
                        dialog.dismiss()
                    }
            })
            setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { dialog, _ ->
                    onCancel.invoke()
                    dialog.cancel()
                }
            )
        }
        return builder.create().apply {
            getWindow().apply {
                setType(WINDOW_TYPE)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            }
            setOnShowListener(DialogInterface.OnShowListener { _ ->
                val editText = requireViewById<EditText>(R.id.controls_pin_input)
                editText.setHint(instructions)
                val useAlphaCheckBox = requireViewById<CheckBox>(R.id.controls_pin_use_alpha)
                useAlphaCheckBox.setChecked(useAlphaNumeric)
                setInputType(editText, useAlphaCheckBox.isChecked())
                requireViewById<CheckBox>(R.id.controls_pin_use_alpha).setOnClickListener { _ ->
                    setInputType(editText, useAlphaCheckBox.isChecked())
                }
                editText.requestFocus()
            })
        }
    }

    /**
     * AlertDialogs to handle [ControlAction#RESPONSE_CHALLENGE_ACK] response type.
     */
    fun createConfirmationDialog(cvh: ControlViewHolder, onCancel: () -> Unit): Dialog? {
        val lastAction = cvh.lastAction
        if (lastAction == null) {
            Log.e(ControlsUiController.TAG,
                "Confirmation Dialog attempted but no last action is set. Will not show")
            return null
        }
        val builder = AlertDialog.Builder(cvh.context, STYLE).apply {
            val res = cvh.context.resources
            setTitle(res.getString(
                R.string.controls_confirmation_message, cvh.title.getText()))
            setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { dialog, _ ->
                    cvh.action(addChallengeValue(lastAction, "true"))
                    dialog.dismiss()
            })
            setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { dialog, _ ->
                    onCancel.invoke()
                    dialog.cancel()
                }
            )
        }
        return builder.create().apply {
            getWindow().apply {
                setType(WINDOW_TYPE)
            }
        }
    }

    private fun setInputType(editText: EditText, useTextInput: Boolean) {
        if (useTextInput) {
            editText.setInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        } else {
            editText.setInputType(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }
    }

    private fun addChallengeValue(action: ControlAction, challengeValue: String): ControlAction {
        val id = action.getTemplateId()
        return when (action) {
            is BooleanAction -> BooleanAction(id, action.getNewState(), challengeValue)
            is FloatAction -> FloatAction(id, action.getNewValue(), challengeValue)
            is CommandAction -> CommandAction(id, challengeValue)
            is ModeAction -> ModeAction(id, action.getNewMode(), challengeValue)
            else -> throw IllegalStateException("'action' is not a known type: $action")
        }
    }
}
