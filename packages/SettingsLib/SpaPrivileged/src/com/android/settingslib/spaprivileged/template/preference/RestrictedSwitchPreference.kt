/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.template.preference

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl
import com.android.settingslib.spaprivileged.template.preference.RestrictedSwitchPreferenceModel.Companion.RestrictedSwitchWrapper

/**
 * @param ifBlockedByAdminOverrideCheckedValueTo if this is not null and there is an admin
 *   restriction, the switch's checked status will be overridden.
 *
 *   And if there is an admin summary, such as "Enabled by admin" or "Disabled by admin", will also
 *   be overridden.
 */
@Composable
fun RestrictedSwitchPreference(
    model: SwitchPreferenceModel,
    restrictions: Restrictions,
    ifBlockedByAdminOverrideCheckedValueTo: Boolean? = null,
) {
    RestrictedSwitchPreference(
        model = model,
        restrictions = restrictions,
        ifBlockedByAdminOverrideCheckedValueTo = ifBlockedByAdminOverrideCheckedValueTo,
        restrictionsProviderFactory = ::RestrictionsProviderImpl,
    )
}

@VisibleForTesting
@Composable
internal fun RestrictedSwitchPreference(
    model: SwitchPreferenceModel,
    restrictions: Restrictions,
    ifBlockedByAdminOverrideCheckedValueTo: Boolean? = null,
    restrictionsProviderFactory: RestrictionsProviderFactory,
) {
    if (restrictions.isEmpty()) {
        SwitchPreference(model)
        return
    }
    restrictionsProviderFactory.RestrictedSwitchWrapper(
        model = model,
        restrictions = restrictions,
        ifBlockedByAdminOverrideCheckedValueTo = ifBlockedByAdminOverrideCheckedValueTo,
    ) {
        SwitchPreference(it)
    }
}
