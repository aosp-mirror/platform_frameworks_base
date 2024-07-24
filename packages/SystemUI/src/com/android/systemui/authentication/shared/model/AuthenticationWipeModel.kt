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

package com.android.systemui.authentication.shared.model

import androidx.annotation.StringRes
import com.android.systemui.res.R

/**
 * Some users have a DevicePolicyManager that requests a user/profile to be wiped after N
 * unsuccessful authentication attempts. This models the pre-wipe state, so that a warning can be
 * shown.
 */
data class AuthenticationWipeModel(
    /** Indicates what part of the user data will be removed. */
    val wipeTarget: WipeTarget,

    /** Unsuccessful authentication attempts since the last successful device entry. */
    val failedAttempts: Int,

    /**
     * Remaining failed authentication attempts before wipe is triggered. 0 indicates a wipe is
     * imminent, no more authentication attempts are allowed.
     */
    val remainingAttempts: Int,
) {
    sealed class WipeTarget(
        @StringRes val messageIdForAlmostWipe: Int,
        @StringRes val messageIdForWipe: Int,
    ) {
        /** The work profile will be removed, which will delete all profile data. */
        data object ManagedProfile :
            WipeTarget(
                messageIdForAlmostWipe = R.string.kg_failed_attempts_almost_at_erase_profile,
                messageIdForWipe = R.string.kg_failed_attempts_now_erasing_profile,
            )

        /** The user will be removed, which will delete all user data. */
        data object User :
            WipeTarget(
                messageIdForAlmostWipe = R.string.kg_failed_attempts_almost_at_erase_user,
                messageIdForWipe = R.string.kg_failed_attempts_now_erasing_user,
            )

        /** The device will be reset and all data will be deleted. */
        data object WholeDevice :
            WipeTarget(
                messageIdForAlmostWipe = R.string.kg_failed_attempts_almost_at_wipe,
                messageIdForWipe = R.string.kg_failed_attempts_now_wiping,
            )
    }
}
