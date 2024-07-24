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

package com.android.systemui.recordissue

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog

const val HAS_APPROVED_SCREEN_RECORDING = "HasApprovedScreenRecord"

class ScreenCapturePermissionDialogDelegate(
    private val dialogFactory: SystemUIDialog.Factory,
    private val sharedPreferences: SharedPreferences,
) : SystemUIDialog.Delegate {

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.apply {
            setIcon(R.drawable.ic_screenrecord)
            setTitle(R.string.screenrecord_permission_dialog_title)
            setMessage(R.string.screenrecord_permission_dialog_warning_entire_screen)
            setNegativeButton(R.string.slice_permission_deny) { _, _ -> cancel() }
            setPositiveButton(R.string.slice_permission_allow) { _, _ ->
                sharedPreferences.edit().putBoolean(HAS_APPROVED_SCREEN_RECORDING, true).apply()
                dismiss()
            }
            window?.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
            window?.setGravity(Gravity.CENTER)
        }
    }

    override fun createDialog(): SystemUIDialog = dialogFactory.create(this)
}
