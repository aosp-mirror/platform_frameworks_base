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

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.enterprise.BaseUserRestricted
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdmin
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.model.enterprise.RestrictedMode
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl

@Composable
fun RestrictedSwitchPreference(
    model: SwitchPreferenceModel,
    restrictions: Restrictions,
    restrictionsProviderFactory: RestrictionsProviderFactory = ::RestrictionsProviderImpl,
) {
    if (restrictions.keys.isEmpty()) {
        SwitchPreference(model)
        return
    }
    val context = LocalContext.current
    val restrictionsProvider = remember(restrictions) {
        restrictionsProviderFactory(context, restrictions)
    }
    val restrictedMode = restrictionsProvider.restrictedModeState().value
    val restrictedSwitchModel = remember(restrictedMode) {
        RestrictedSwitchPreferenceModel(context, model, restrictedMode)
    }
    restrictedSwitchModel.RestrictionWrapper {
        SwitchPreference(restrictedSwitchModel)
    }
}

internal object RestrictedSwitchPreference {
    fun getSummary(
        context: Context,
        restrictedMode: RestrictedMode?,
        summaryIfNoRestricted: State<String>,
        checked: State<Boolean?>,
    ): State<String> = when (restrictedMode) {
        is NoRestricted -> summaryIfNoRestricted
        is BaseUserRestricted -> stateOf(context.getString(R.string.disabled))
        is BlockedByAdmin -> derivedStateOf { restrictedMode.getSummary(checked.value) }
        null -> stateOf(context.getString(R.string.summary_placeholder))
    }
}

private class RestrictedSwitchPreferenceModel(
    context: Context,
    model: SwitchPreferenceModel,
    private val restrictedMode: RestrictedMode?,
) : SwitchPreferenceModel {
    override val title = model.title

    override val summary = RestrictedSwitchPreference.getSummary(
        context = context,
        restrictedMode = restrictedMode,
        summaryIfNoRestricted = model.summary,
        checked = model.checked,
    )

    override val checked = when (restrictedMode) {
        null -> stateOf(null)
        is NoRestricted -> model.checked
        is BaseUserRestricted -> stateOf(false)
        is BlockedByAdmin -> model.checked
    }

    override val changeable = when (restrictedMode) {
        null -> stateOf(false)
        is NoRestricted -> model.changeable
        is BaseUserRestricted -> stateOf(false)
        is BlockedByAdmin -> stateOf(false)
    }

    override val onCheckedChange = when (restrictedMode) {
        null -> null
        is NoRestricted -> model.onCheckedChange
        // Need to pass a non null onCheckedChange to enable semantics ToggleableState, although
        // since changeable is false this will not be called.
        is BaseUserRestricted -> model.onCheckedChange
        // Pass null since semantics ToggleableState is provided in RestrictionWrapper.
        is BlockedByAdmin -> null
    }

    @Composable
    fun RestrictionWrapper(content: @Composable () -> Unit) {
        if (restrictedMode !is BlockedByAdmin) {
            content()
            return
        }
        Box(
            Modifier
                .clickable(
                    role = Role.Switch,
                    onClick = { restrictedMode.sendShowAdminSupportDetailsIntent() },
                )
                .semantics {
                    this.toggleableState = ToggleableState(checked.value)
                },
        ) { content() }
    }

    private fun ToggleableState(value: Boolean?) = when (value) {
        true -> ToggleableState.On
        false -> ToggleableState.Off
        null -> ToggleableState.Indeterminate
    }
}
