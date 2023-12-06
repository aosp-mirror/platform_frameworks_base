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

package com.android.packageinstaller.v2.model.installstagedata

import android.app.Activity
import android.content.Intent

class InstallAborted(
    val abortReason: Int,
    /**
     * It will hold the restriction name, when the restriction was enforced by the system, and not
     * a device admin.
     */
    val message: String? = null,
    /**
     * * If abort reason is [ABORT_REASON_POLICY], then this will hold the Intent
     * to display a support dialog when a feature was disabled by an admin. It will be
     * `null` if the feature is disabled by the system. In this case, the restriction name
     * will be set in [message]
     * * If the abort reason is [ABORT_REASON_INTERNAL_ERROR], it **may** hold an
     * intent to be sent as a result to the calling activity.
     */
    val resultIntent: Intent? = null,
    val activityResultCode: Int = Activity.RESULT_CANCELED,
    val errorDialogType: Int? = 0,
) : InstallStage() {

    override val stageCode = STAGE_ABORTED

    companion object {
        const val ABORT_REASON_INTERNAL_ERROR = 0
        const val ABORT_REASON_POLICY = 1
        const val ABORT_REASON_DONE = 2
        const val DLG_PACKAGE_ERROR = 1
    }
}
