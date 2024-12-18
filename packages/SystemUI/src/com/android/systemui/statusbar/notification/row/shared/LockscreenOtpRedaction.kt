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

package com.android.systemui.statusbar.notification.row.shared

import android.app.Flags
import android.app.Flags.redactSensitiveContentNotificationsOnLockscreen
import com.android.systemui.flags.FlagToken

/** Helper for reading or using the async hybrid view inflation flag state. */
object LockscreenOtpRedaction {
    const val FLAG_NAME = Flags.FLAG_REDACT_SENSITIVE_CONTENT_NOTIFICATIONS_ON_LOCKSCREEN

    /** A token used for dependency declaration */
    val token: FlagToken
        get() = FlagToken(FLAG_NAME, isEnabled)

    @JvmStatic
    inline val isEnabled
        get() =
            redactSensitiveContentNotificationsOnLockscreen() && AsyncHybridViewInflation.isEnabled
}
