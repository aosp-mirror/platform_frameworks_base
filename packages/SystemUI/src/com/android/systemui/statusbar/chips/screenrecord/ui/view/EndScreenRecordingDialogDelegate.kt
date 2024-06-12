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

import android.os.Bundle
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog

/** A dialog that lets the user stop an ongoing screen recording. */
class EndScreenRecordingDialogDelegate(
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    private val interactor: ScreenRecordChipInteractor,
) : SystemUIDialog.Delegate {

    override fun createDialog(): SystemUIDialog {
        return systemUIDialogFactory.create(this)
    }

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        with(dialog) {
            setIcon(ScreenRecordChipInteractor.ICON)
            setTitle(R.string.screenrecord_stop_dialog_title)
            // TODO(b/332662551): Use a different message if they're sharing just a single app.
            setMessage(R.string.screenrecord_stop_dialog_message)
            // No custom on-click, because the dialog will automatically be dismissed when the
            // button is clicked anyway.
            setNegativeButton(R.string.close_dialog_button, /* onClick= */ null)
            setPositiveButton(R.string.screenrecord_stop_dialog_button) { _, _ ->
                interactor.stopRecording()
            }
        }
    }
}
