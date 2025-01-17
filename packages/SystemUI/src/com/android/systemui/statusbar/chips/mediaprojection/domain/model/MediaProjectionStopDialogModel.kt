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
            dialog.setOnCancelListener { onDismissAction.invoke() }
            dialog.setOnDismissListener { onDismissAction.invoke() }
            dialog.show()
        }
    }
}
