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

package com.android.systemui.statusbar.notification.shared

import com.android.systemui.flags.FlagToken

/** Helper for reading or using the heads-up cycling flag state. */
@Suppress("NOTHING_TO_INLINE")
object NotificationHeadsUpCycling {
    /** The aconfig flag name */
    const val FLAG_NAME = NotificationThrottleHun.FLAG_NAME

    /** A token used for dependency declaration */
    val token: FlagToken
        get() = NotificationThrottleHun.token

    /** Is the heads-up cycling animation enabled */
    @JvmStatic
    inline val isEnabled
        get() = NotificationThrottleHun.isEnabled

    /** Whether to animate the bottom line when transiting from a tall HUN to a short HUN */
    @JvmStatic
    inline val animateTallToShort
        get() = false

    /**
     * Called to ensure code is only run when the flag is enabled. This protects users from the
     * unintended behaviors caused by accidentally running new logic, while also crashing on an eng
     * build to ensure that the refactor author catches issues in testing.
     */
    @JvmStatic
    inline fun isUnexpectedlyInLegacyMode() = NotificationThrottleHun.isUnexpectedlyInLegacyMode()

    /**
     * Called to ensure code is only run when the flag is disabled. This will throw an exception if
     * the flag is enabled to ensure that the refactor author catches issues in testing.
     */
    @JvmStatic
    inline fun assertInLegacyMode() = NotificationThrottleHun.assertInLegacyMode()
}
