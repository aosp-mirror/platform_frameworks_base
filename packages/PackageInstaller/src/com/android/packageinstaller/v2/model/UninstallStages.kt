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
package com.android.packageinstaller.v2.model

import android.app.Activity
import android.app.Notification
import android.content.Intent
import com.android.packageinstaller.R

sealed class UninstallStage(val stageCode: Int) {

    companion object {
        const val STAGE_DEFAULT = -1
        const val STAGE_ABORTED = 0
        const val STAGE_READY = 1
        const val STAGE_USER_ACTION_REQUIRED = 2
        const val STAGE_UNINSTALLING = 3
        const val STAGE_SUCCESS = 4
        const val STAGE_FAILED = 5
    }
}

class UninstallReady : UninstallStage(STAGE_READY)

data class UninstallUserActionRequired(
    val title: String? = null,
    val message: String? = null,
    val appDataSize: Long = 0,
    val isArchive: Boolean = false
) : UninstallStage(STAGE_USER_ACTION_REQUIRED)

data class UninstallUninstalling(val appLabel: CharSequence, val isCloneUser: Boolean) :
    UninstallStage(STAGE_UNINSTALLING)

data class UninstallSuccess(
    val resultIntent: Intent? = null,
    val activityResultCode: Int = 0,
    val message: String? = null,
) : UninstallStage(STAGE_SUCCESS)

data class UninstallFailed(
    val returnResult: Boolean,
    /**
     * If the caller wants the result back, the intent will hold the uninstall failure status code
     * and legacy code.
     */
    val resultIntent: Intent? = null,
    val activityResultCode: Int = Activity.RESULT_CANCELED,
    /**
     * ID used to show [uninstallNotification]
     */
    val uninstallNotificationId: Int? = null,
    /**
     * When the user does not request a result back, this notification will be shown indicating the
     * reason for uninstall failure.
     */
    val uninstallNotification: Notification? = null,
) : UninstallStage(STAGE_FAILED) {

    init {
        if (uninstallNotification != null && uninstallNotificationId == null) {
            throw IllegalArgumentException(
                "uninstallNotification cannot be set without uninstallNotificationId"
            )
        }
    }
}

data class UninstallAborted(val abortReason: Int) : UninstallStage(STAGE_ABORTED) {

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

            ABORT_REASON_UNINSTALL_DONE -> {
                dialogTitleResource = 0
                dialogTextResource = 0
            }

            else -> {
                dialogTitleResource = 0
                dialogTextResource = R.string.generic_error_dlg_text
            }
        }
    }

    companion object {
        const val ABORT_REASON_GENERIC_ERROR = 0
        const val ABORT_REASON_APP_UNAVAILABLE = 1
        const val ABORT_REASON_USER_NOT_ALLOWED = 2
        const val ABORT_REASON_UNINSTALL_DONE = 3
    }
}

