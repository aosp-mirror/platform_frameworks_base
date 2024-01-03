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

package com.android.systemui.controls.management

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import java.util.function.Consumer
import javax.inject.Inject

/**
 * Factory to create dialogs for consenting to show app panels for specific apps.
 *
 * [dialogFactory] is for facilitating testing.
 */
class PanelConfirmationDialogFactory @Inject constructor(
        private val dialogFactory: SystemUIDialog.Factory
) {

    /**
     * Creates a dialog to show to the user. [response] will be true if an only if the user responds
     * affirmatively.
     */
    fun createConfirmationDialog(
            context: Context,
            appName: CharSequence,
            response: Consumer<Boolean>
    ): Dialog {
        val listener =
            DialogInterface.OnClickListener { _, which ->
                response.accept(which == DialogInterface.BUTTON_POSITIVE)
            }
        return dialogFactory.create(context).apply {
            setTitle(this.context.getString(R.string.controls_panel_authorization_title, appName))
            setMessage(this.context.getString(R.string.controls_panel_authorization, appName))
            setCanceledOnTouchOutside(true)
            setOnCancelListener { response.accept(false) }
            setPositiveButton(R.string.controls_dialog_ok, listener)
            setNeutralButton(R.string.cancel, listener)
        }
    }
}
