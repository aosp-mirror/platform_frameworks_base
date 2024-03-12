/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.recordissue

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Switch
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog

class RecordIssueDialogDelegate(
    private val factory: SystemUIDialog.Factory,
    private val onStarted: Runnable
) : SystemUIDialog.Delegate {

    @SuppressLint("UseSwitchCompatOrMaterialCode") private lateinit var screenRecordSwitch: Switch
    private lateinit var issueTypeButton: Button

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.apply {
            setView(LayoutInflater.from(context).inflate(R.layout.record_issue_dialog, null))
            setTitle(context.getString(R.string.qs_record_issue_label))
            setIcon(R.drawable.qs_record_issue_icon_off)
            setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
            setPositiveButton(R.string.qs_record_issue_start) { _, _ ->
                onStarted.run()
                dismiss()
            }
        }
    }

    override fun createDialog(): SystemUIDialog = factory.create(this)

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.apply {
            window?.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
            window?.setGravity(Gravity.CENTER)

            screenRecordSwitch = requireViewById(R.id.screenrecord_switch)
            issueTypeButton = requireViewById(R.id.issue_type_button)
            issueTypeButton.setOnClickListener { onIssueTypeClicked(context) }
        }
    }

    private fun onIssueTypeClicked(context: Context) {
        val selectedCategory = issueTypeButton.text.toString()
        val popupMenu = PopupMenu(context, issueTypeButton)

        context.resources.getStringArray(R.array.qs_record_issue_types).forEachIndexed { i, cat ->
            popupMenu.menu.add(0, 0, i, cat).apply {
                setIcon(R.drawable.arrow_pointing_down)
                if (selectedCategory != cat) {
                    iconTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                }
            }
        }
        popupMenu.apply {
            setOnMenuItemClickListener {
                issueTypeButton.text = it.title
                true
            }
            setForceShowIcon(true)
            show()
        }
    }
}
