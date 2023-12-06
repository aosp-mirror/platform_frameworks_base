/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.model.uninstallstagedata

import android.app.Activity
import com.android.packageinstaller.R

class UninstallAborted(val abortReason: Int) : UninstallStage() {

    var dialogTitleResource = 0
    var dialogTextResource = 0
    val activityResultCode = Activity.RESULT_FIRST_USER

    init {
        when (abortReason) {
            ABORT_REASON_APP_UNAVAILABLE -> {
                dialogTitleResource = R.string.app_not_found_dlg_title
                dialogTextResource = R.string.app_not_found_dlg_text
            }

            ABORT_REASON_USER_NOT_ALLOWED -> {
                dialogTitleResource = 0
                dialogTextResource = R.string.user_is_not_allowed_dlg_text
            }

            else -> {
                dialogTitleResource = 0
                dialogTextResource = R.string.generic_error_dlg_text
            }
        }
    }

    override val stageCode = STAGE_ABORTED

    companion object {
        const val ABORT_REASON_GENERIC_ERROR = 0
        const val ABORT_REASON_APP_UNAVAILABLE = 1
        const val ABORT_REASON_USER_NOT_ALLOWED = 2
    }
}
