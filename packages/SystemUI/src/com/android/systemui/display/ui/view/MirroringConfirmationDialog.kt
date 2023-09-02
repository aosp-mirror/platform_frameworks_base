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
package com.android.systemui.display.ui.view

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.android.systemui.R

/**
 * Dialog used to decide what to do with a connected display.
 *
 * [onCancelMirroring] is called **only** if mirroring didn't start, or when the dismiss button is
 * pressed.
 */
class MirroringConfirmationDialog(
    context: Context,
    private val onStartMirroringClickListener: View.OnClickListener,
    private val onCancelMirroring: View.OnClickListener,
) : Dialog(context, R.style.Theme_SystemUI_Dialog) {

    private lateinit var mirrorButton: TextView
    private lateinit var dismissButton: TextView
    private var enabledPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
            addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
            setGravity(Gravity.BOTTOM)
        }
        setContentView(R.layout.connected_display_dialog)
        setCanceledOnTouchOutside(true)
        mirrorButton =
            requireViewById<TextView>(R.id.enable_display).apply {
                setOnClickListener(onStartMirroringClickListener)
                enabledPressed = true
            }
        dismissButton =
            requireViewById<TextView>(R.id.cancel).apply { setOnClickListener(onCancelMirroring) }

        setOnDismissListener {
            if (!enabledPressed) {
                onCancelMirroring.onClick(null)
            }
        }
    }
}
