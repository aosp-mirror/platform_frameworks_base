/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification

import android.content.Context
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.FlagResolver
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.NotificationFlags
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

class NotifPipelineFlags @Inject constructor(
    val context: Context,
    val featureFlags: FeatureFlags,
    val sysPropFlags: FlagResolver,
) {
    fun isDevLoggingEnabled(): Boolean =
        featureFlags.isEnabled(Flags.NOTIFICATION_PIPELINE_DEVELOPER_LOGGING)

    fun allowDismissOngoing(): Boolean =
            sysPropFlags.isEnabled(NotificationFlags.ALLOW_DISMISS_ONGOING)

    fun isOtpRedactionEnabled(): Boolean =
            sysPropFlags.isEnabled(NotificationFlags.OTP_REDACTION)

    val isNoHunForOldWhenEnabled: Boolean
        get() = featureFlags.isEnabled(Flags.NO_HUN_FOR_OLD_WHEN)
}
