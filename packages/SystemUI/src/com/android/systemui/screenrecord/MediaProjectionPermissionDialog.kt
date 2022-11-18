/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.screenrecord

import android.content.Context
import android.os.Bundle
import com.android.systemui.R

/** Dialog to select screen recording options */
class MediaProjectionPermissionDialog(
    context: Context?,
    private val onStartRecordingClicked: Runnable,
    appName: String?
) : BaseScreenSharePermissionDialog(context, createOptionList(), appName) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDialogTitle(R.string.media_projection_permission_dialog_title)
        setStartButtonText(R.string.media_projection_permission_dialog_continue)
        setStartButtonOnClickListener {
            // Note that it is important to run this callback before dismissing, so that the
            // callback can disable the dialog exit animation if it wants to.
            onStartRecordingClicked.run()
            dismiss()
        }
    }

    companion object {
        private fun createOptionList(): List<ScreenShareOption> {
            return listOf(
                ScreenShareOption(
                    ENTIRE_SCREEN,
                    R.string.media_projection_permission_dialog_option_entire_screen,
                    R.string.media_projection_permission_dialog_warning_entire_screen
                ),
                ScreenShareOption(
                    SINGLE_APP,
                    R.string.media_projection_permission_dialog_option_single_app,
                    R.string.media_projection_permission_dialog_warning_single_app
                )
            )
        }
    }
}
