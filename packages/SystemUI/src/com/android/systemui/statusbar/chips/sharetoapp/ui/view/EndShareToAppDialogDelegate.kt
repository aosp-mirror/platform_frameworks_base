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
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel.Companion.SHARE_TO_APP_ICON
import com.android.systemui.statusbar.phone.SystemUIDialog

/** A dialog that lets the user stop an ongoing share-screen-to-app event. */
class EndShareToAppDialogDelegate(
    private val endMediaProjectionDialogHelper: EndMediaProjectionDialogHelper,
    private val context: Context,
    private val stopAction: () -> Unit,
    private val state: ProjectionChipModel.Projecting,
) : SystemUIDialog.Delegate {
    override fun createDialog(): SystemUIDialog {
        return endMediaProjectionDialogHelper.createDialog(this)
    }

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        with(dialog) {
            setIcon(SHARE_TO_APP_ICON)
            setTitle(R.string.share_to_app_stop_dialog_title)
            setMessage(getMessage())
            // No custom on-click, because the dialog will automatically be dismissed when the
            // button is clicked anyway.
            setNegativeButton(R.string.close_dialog_button, /* onClick= */ null)
            setPositiveButton(
                R.string.share_to_app_stop_dialog_button,
                endMediaProjectionDialogHelper.wrapStopAction(stopAction),
            )
        }
    }

    private fun getMessage(): String {
        return if (state.projectionState is MediaProjectionState.Projecting.SingleTask) {
            // If a single app is being shared, use the name of the app being shared in the dialog
            val appBeingSharedName =
                endMediaProjectionDialogHelper.getAppName(state.projectionState)
            if (appBeingSharedName != null) {
                context.getString(
                    R.string.share_to_app_stop_dialog_message_single_app_specific,
                    appBeingSharedName,
                )
            } else {
                context.getString(R.string.share_to_app_stop_dialog_message_single_app_generic)
            }
        } else {
            // Otherwise, use the name of the app *receiving* the share
            val hostAppName =
                endMediaProjectionDialogHelper.getAppName(state.projectionState.hostPackage)
            if (hostAppName != null) {
                context.getString(
                    R.string.share_to_app_stop_dialog_message_entire_screen_with_host_app,
                    hostAppName
                )
            } else {
                context.getString(R.string.share_to_app_stop_dialog_message_entire_screen)
            }
        }
    }
}
