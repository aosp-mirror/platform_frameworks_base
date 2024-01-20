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

package com.android.systemui.keyguard.shared.model

import com.android.internal.widget.LockPatternUtils

/** Authentication flags corresponding to a user. */
data class AuthenticationFlags(val userId: Int, val flag: Int) {
    val isInUserLockdown =
        containsFlag(
            flag,
            LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN
        )

    val isPrimaryAuthRequiredAfterReboot =
        containsFlag(flag, LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT)

    val isPrimaryAuthRequiredAfterTimeout =
        containsFlag(flag, LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT)

    val isPrimaryAuthRequiredAfterDpmLockdown =
        containsFlag(
            flag,
            LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW
        )

    val someAuthRequiredAfterUserRequest =
        containsFlag(flag, LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST)

    val someAuthRequiredAfterTrustAgentExpired =
        containsFlag(
            flag,
            LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED
        )

    val primaryAuthRequiredForUnattendedUpdate =
        containsFlag(
            flag,
            LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE
        )

    /** Either Class 3 biometrics or primary auth can be used to unlock the device. */
    val strongerAuthRequiredAfterNonStrongBiometricsTimeout =
        containsFlag(
            flag,
            LockPatternUtils.StrongAuthTracker
                .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT
        )

    val isSomeAuthRequiredAfterAdaptiveAuthRequest =
        containsFlag(
            flag,
            LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST
        )
}

private fun containsFlag(haystack: Int, needle: Int): Boolean {
    return haystack and needle != 0
}
