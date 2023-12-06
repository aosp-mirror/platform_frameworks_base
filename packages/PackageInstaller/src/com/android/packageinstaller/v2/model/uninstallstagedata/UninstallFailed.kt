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
import android.app.Notification
import android.content.Intent

class UninstallFailed(
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
) : UninstallStage() {

    override val stageCode = STAGE_FAILED

    init {
        if (uninstallNotification != null && uninstallNotificationId == null) {
            throw IllegalArgumentException(
                "uninstallNotification cannot be set without uninstallNotificationId"
            )
        }
    }
}
