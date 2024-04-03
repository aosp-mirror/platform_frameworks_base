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
package com.android.systemui.mediaprojection.devicepolicy

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface.BUTTON_POSITIVE
import android.content.res.Resources
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject

/** Dialog that shows that screen capture is disabled on this device. */
class ScreenCaptureDisabledDialogDelegate
@Inject
constructor(
    private val context: Context,
    @Main private val resources: Resources,
) {

    fun createPlainDialog(): AlertDialog {
        return AlertDialog.Builder(context, R.style.Theme_SystemUI_Dialog).create().also {
            initDialog(it)
        }
    }

    fun createSysUIDialog(): AlertDialog {
        return SystemUIDialog(context).also { initDialog(it) }
    }

    private fun initDialog(dialog: AlertDialog) {
        dialog.setTitle(
            resources.getString(R.string.screen_capturing_disabled_by_policy_dialog_title)
        )
        dialog.setMessage(
            resources.getString(R.string.screen_capturing_disabled_by_policy_dialog_description)
        )
        dialog.setIcon(R.drawable.ic_cast)
        dialog.setButton(BUTTON_POSITIVE, resources.getString(android.R.string.ok)) { _, _ ->
            dialog.cancel()
        }
    }
}
