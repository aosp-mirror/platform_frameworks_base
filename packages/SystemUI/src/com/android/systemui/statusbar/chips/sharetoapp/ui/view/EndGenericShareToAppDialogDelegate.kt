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

package com.android.systemui.statusbar.chips.sharetoapp.ui.view

import android.content.Context
import android.os.Bundle
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel.Companion.SHARE_TO_APP_ICON
import com.android.systemui.statusbar.phone.SystemUIDialog

/**
 * A dialog that lets the user stop an ongoing share-to-app event. The user could be sharing their
 * screen or just sharing their audio. This dialog uses generic strings to handle both cases well.
 */
class EndGenericShareToAppDialogDelegate(
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    private val context: Context,
    private val stopAction: () -> Unit,
) : SystemUIDialog.Delegate {
    override fun createDialog(): SystemUIDialog {
        return endMediaProjectionDialogHelper.createDialog(this)
    }

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        val message = context.getString(R.string.share_to_app_stop_dialog_message_generic)
        with(dialog) {
            setIcon(SHARE_TO_APP_ICON)
            setTitle(R.string.share_to_app_stop_dialog_title_generic)
            setMessage(message)
            // No custom on-click, because the dialog will automatically be dismissed when the
            // button is clicked anyway.
            setNegativeButton(R.string.close_dialog_button, /* onClick= */ null)
            setPositiveButton(
                R.string.share_to_app_stop_dialog_button,
                endMediaProjectionDialogHelper.wrapStopAction(stopAction),
            )
        }
    }
}
