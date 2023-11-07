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
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spaprivileged.framework.compose.getPlaceholder
import com.android.settingslib.spaprivileged.model.enterprise.BaseUserRestricted
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdmin
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.model.enterprise.RestrictedMode
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl
import com.android.settingslib.spaprivileged.model.enterprise.rememberRestrictedMode

@Composable
fun RestrictedSwitchPreference(
    model: SwitchPreferenceModel,
    restrictions: Restrictions,
) {
    RestrictedSwitchPreference(model, restrictions, ::RestrictionsProviderImpl)
}

@VisibleForTesting
@Composable
internal fun RestrictedSwitchPreference(
    model: SwitchPreferenceModel,
    restrictions: Restrictions,
    restrictionsProviderFactory: RestrictionsProviderFactory,
) {
    if (restrictions.keys.isEmpty()) {
        SwitchPreference(model)
        return
    }
    val context = LocalContext.current
    val restrictedMode = restrictionsProviderFactory.rememberRestrictedMode(restrictions).value
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
        restrictedModeSupplier: () -> RestrictedMode?,
        summaryIfNoRestricted: () -> String,
        checked: () -> Boolean?,
    ): () -> String = {
        when (val restrictedMode = restrictedModeSupplier()) {
            is NoRestricted -> summaryIfNoRestricted()
            is BaseUserRestricted -> context.getString(com.android.settingslib.R.string.disabled)
            is BlockedByAdmin -> restrictedMode.getSummary(checked())
            null -> context.getPlaceholder()
        }
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
        restrictedModeSupplier = { restrictedMode },
        summaryIfNoRestricted = model.summary,
        checked = model.checked,
    )

    override val checked = when (restrictedMode) {
        null -> ({ null })
        is NoRestricted -> model.checked
        is BaseUserRestricted -> ({ false })
        is BlockedByAdmin -> model.checked
    }

    override val changeable = when (restrictedMode) {
        null -> ({ false })
        is NoRestricted -> model.changeable
        is BaseUserRestricted -> ({ false })
        is BlockedByAdmin -> ({ false })
    }

    override val onCheckedChange = when (restrictedMode) {
        null -> null
        is NoRestricted -> model.onCheckedChange
        // Need to passthrough onCheckedChange for toggleable semantics, although since changeable
        // is false so this will not be called.
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
                    this.toggleableState = ToggleableState(checked())
                },
        ) { content() }
    }

    private fun ToggleableState(value: Boolean?) = when (value) {
        true -> ToggleableState.On
        false -> ToggleableState.Off
        null -> ToggleableState.Indeterminate
    }
}
