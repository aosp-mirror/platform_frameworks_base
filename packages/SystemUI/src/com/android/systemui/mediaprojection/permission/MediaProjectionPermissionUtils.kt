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

package com.android.systemui.mediaprojection.permission

import android.content.Context
import android.media.projection.MediaProjectionConfig
import com.android.systemui.res.R

/** Various utility methods related to media projection permissions. */
object MediaProjectionPermissionUtils {
    fun getSingleAppDisabledText(
        context: Context,
        appName: String,
        mediaProjectionConfig: MediaProjectionConfig?,
        overrideDisableSingleAppOption: Boolean,
    ): String? {
        // The single app option should only be disabled if the client has setup a
        // MediaProjection with MediaProjectionConfig#createConfigForDefaultDisplay AND
        // it hasn't been overridden by the OVERRIDE_DISABLE_SINGLE_APP_OPTION per-app override.
        val singleAppOptionDisabled =
            !overrideDisableSingleAppOption &&
                mediaProjectionConfig?.regionToCapture ==
                    MediaProjectionConfig.CAPTURE_REGION_FIXED_DISPLAY
        return if (singleAppOptionDisabled) {
            context.getString(
                R.string.media_projection_entry_app_permission_dialog_single_app_disabled,
                appName,
            )
        } else {
            null
        }
    }
}
