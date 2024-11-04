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

package com.android.settingslib

import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin

interface RestrictedInterface {
    fun useAdminDisabledSummary(useSummary: Boolean)

    fun checkRestrictionAndSetDisabled(userRestriction: String)

    fun checkRestrictionAndSetDisabled(userRestriction: String, userId: Int)

    /**
     * Checks if the given setting is subject to Enhanced Confirmation Mode restrictions for this
     * package. Marks the preference as disabled if so.
     *
     * @param settingIdentifier The key identifying the setting
     * @param packageName       the package to check the settingIdentifier for
     */
    fun checkEcmRestrictionAndSetDisabled(
        settingIdentifier: String,
        packageName: String
    )

    val isDisabledByAdmin: Boolean

    fun setDisabledByAdmin(admin: EnforcedAdmin?)

    val isDisabledByEcm: Boolean

    val uid: Int

    val packageName: String?
}
