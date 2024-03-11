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

package com.android.settingslib.spaprivileged.template.preference

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.preference.TwoTargetSwitchPreference
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl
import com.android.settingslib.spaprivileged.model.enterprise.rememberRestrictedMode
import com.android.settingslib.spaprivileged.template.preference.RestrictedSwitchPreferenceModel.Companion.RestrictedSwitchWrapper

@Composable
fun RestrictedTwoTargetSwitchPreference(
    model: SwitchPreferenceModel,
    icon: @Composable (() -> Unit)? = null,
    restrictions: Restrictions,
    primaryEnabled: () -> Boolean = { true },
    primaryOnClick: (() -> Unit)?,
) {
    RestrictedTwoTargetSwitchPreference(
        model = model,
        icon = icon,
        primaryEnabled = primaryEnabled,
        primaryOnClick = primaryOnClick,
        restrictions = restrictions,
        restrictionsProviderFactory = ::RestrictionsProviderImpl,
    )
}

@VisibleForTesting
@Composable
internal fun RestrictedTwoTargetSwitchPreference(
    model: SwitchPreferenceModel,
    icon: @Composable (() -> Unit)? = null,
    primaryEnabled: () -> Boolean = { true },
    primaryOnClick: (() -> Unit)?,
    restrictions: Restrictions,
    restrictionsProviderFactory: RestrictionsProviderFactory,
) {
    if (restrictions.isEmpty()) {
        TwoTargetSwitchPreference(model, icon, primaryEnabled, primaryOnClick)
        return
    }
    val restrictedMode = restrictionsProviderFactory.rememberRestrictedMode(restrictions).value
    RestrictedSwitchWrapper(model, restrictedMode) { restrictedModel ->
        TwoTargetSwitchPreference(
            model = restrictedModel,
            icon = icon,
            primaryEnabled = restrictedMode.restrictEnabled(primaryEnabled),
            primaryOnClick = restrictedMode.restrictOnClick(primaryOnClick),
        )
    }
}
