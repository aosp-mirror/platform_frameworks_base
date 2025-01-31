/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.chips.mediaprojection.domain.model

import com.android.systemui.statusbar.phone.SystemUIDialog

/** Represents the visibility state of a media projection stop dialog. */
sealed interface MediaProjectionStopDialogModel {
    /** The dialog is hidden and not visible to the user. */
    data object Hidden : MediaProjectionStopDialogModel

    /** The dialog is shown to the user. */
    data class Shown(
        val dialogDelegate: SystemUIDialog.Delegate,
        private val onDismissAction: () -> Unit,
    ) : MediaProjectionStopDialogModel {
        /**
         * Creates and shows the dialog. Ensures that onDismissAction callback is invoked when the
         * dialog is canceled or dismissed.
         */
        fun createAndShowDialog() {
            val dialog = dialogDelegate.createDialog()
            // Prevents the dialog from being dismissed by tapping outside its boundary.
            // This is specifically required for the stop dialog shown at call end (i.e.,
            // PROJECTION_STARTED_DURING_CALL_AND_ACTIVE_POST_CALL event) to disallow remote
            // dismissal by external devices. Other media projection stop dialogs do not require
            // this since they are triggered explicitly by tapping the status bar chip, in which
            // case the full screen containing the dialog is not remote dismissible.
            dialog.setCanceledOnTouchOutside(/* cancel= */ false)
            dialog.setOnCancelListener { onDismissAction.invoke() }
            dialog.setOnDismissListener { onDismissAction.invoke() }
            dialog.show()
        }
    }
}
