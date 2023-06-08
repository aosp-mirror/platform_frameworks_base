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
 *
 */

package com.android.systemui.controls.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import com.android.systemui.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import java.util.function.Consumer
import javax.inject.Inject

class ControlsDialogsFactory(private val internalDialogFactory: (Context) -> SystemUIDialog) {

    @Inject constructor() : this({ SystemUIDialog(it) })

    fun createRemoveAppDialog(
        context: Context,
        appName: CharSequence,
        response: Consumer<Boolean>
    ): Dialog {
        val listener =
            DialogInterface.OnClickListener { _, which ->
                response.accept(which == DialogInterface.BUTTON_POSITIVE)
            }
        return internalDialogFactory(context).apply {
            setTitle(context.getString(R.string.controls_panel_remove_app_authorization, appName))
            setCanceledOnTouchOutside(true)
            setOnCancelListener { response.accept(false) }
            setPositiveButton(R.string.controls_dialog_remove, listener)
            setNeutralButton(R.string.cancel, listener)
        }
    }
}
