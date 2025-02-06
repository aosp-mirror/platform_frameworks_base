/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.chips.screenrecord.ui.view

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.View
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel
import com.android.systemui.statusbar.phone.SystemUIDialog

/** A dialog that lets the user stop an ongoing screen recording. */
class EndScreenRecordingDialogDelegate(
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    val context: Context,
    private val stopAction: () -> Unit,
    private val recordedTask: ActivityManager.RunningTaskInfo?,
) : SystemUIDialog.Delegate {

    override fun createDialog(): SystemUIDialog {
        return endMediaProjectionDialogHelper.createDialog(this)
    }

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        val appName = endMediaProjectionDialogHelper.getAppName(recordedTask)
        val message =
            if (appName != null) {
                context.getString(R.string.screenrecord_stop_dialog_message_specific_app, appName)
            } else {
                context.getString(R.string.screenrecord_stop_dialog_message)
            }

        with(dialog) {
            setIcon(ScreenRecordChipViewModel.ICON)
            setTitle(R.string.screenrecord_stop_dialog_title)
            setMessage(message)
            // No custom on-click, because the dialog will automatically be dismissed when the
            // button is clicked anyway.
            setNegativeButton(R.string.close_dialog_button, /* onClick= */ null)
            setPositiveButton(
                R.string.screenrecord_stop_dialog_button,
                endMediaProjectionDialogHelper.wrapStopAction(stopAction),
            )
            if (com.android.media.projection.flags.Flags.showStopDialogPostCallEnd()) {
                window
                    ?.decorView
                    ?.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
            }
        }
    }
}
