/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.user

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import com.android.systemui.qs.PseudoGridView
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.R

/**
 * Dialog for switching users or creating new ones.
 */
class UserDialog(
    context: Context
) : SystemUIDialog(context, R.style.Theme_SystemUI_Dialog_QSDialog) {

    // create() is no-op after creation
    private lateinit var _doneButton: View
    /**
     * Button with text "Done" in dialog.
     */
    val doneButton: View
        get() {
            create()
            return _doneButton
        }

    private lateinit var _settingsButton: View
    /**
     * Button with text "User Settings" in dialog.
     */
    val settingsButton: View
        get() {
            create()
            return _settingsButton
        }

    private lateinit var _grid: PseudoGridView
    /**
     * Grid to populate with user avatar from adapter
     */
    val grid: ViewGroup
        get() {
            create()
            return _grid
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
            attributes.fitInsetsTypes = attributes.fitInsetsTypes or WindowInsets.Type.statusBars()
            attributes.receiveInsetsIgnoringZOrder = true
            setLayout(
                    context.resources.getDimensionPixelSize(R.dimen.qs_panel_width),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
        setContentView(R.layout.qs_user_dialog_content)

        _doneButton = requireViewById(R.id.done)
        _settingsButton = requireViewById(R.id.settings)
        _grid = requireViewById(R.id.grid)
    }
}