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

package com.android.settingslib.spaprivileged.template.preference

import android.content.Context
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
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spaprivileged.framework.compose.getPlaceholder
import com.android.settingslib.spaprivileged.model.enterprise.BaseUserRestricted
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdmin
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByEcm
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.model.enterprise.RestrictedMode
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.rememberRestrictedMode

internal class RestrictedSwitchPreferenceModel(
    context: Context,
    model: SwitchPreferenceModel,
    private val restrictedMode: RestrictedMode?,
) : SwitchPreferenceModel {
    override val title = model.title

    override val summary = getSummary(
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
        is BlockedByEcm -> model.checked
    }

    override val changeable = if (restrictedMode is NoRestricted) model.changeable else ({ false })

    override val onCheckedChange = when (restrictedMode) {
        null -> null
        is NoRestricted -> model.onCheckedChange
        // Need to passthrough onCheckedChange for toggleable semantics, although since changeable
        // is false so this will not be called.
        is BaseUserRestricted -> model.onCheckedChange
        // Pass null since semantics ToggleableState is provided in RestrictionWrapper.
        is BlockedByAdmin -> null
        is BlockedByEcm -> null
    }

    @Composable
    fun RestrictionWrapper(content: @Composable () -> Unit) {
        when (restrictedMode) {
            is BlockedByAdmin -> {
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

            is BlockedByEcm -> {
                Box(
                    Modifier
                            .clickable(
                                role = Role.Switch,
                                onClick = { restrictedMode.showRestrictedSettingsDetails() },
                            )
                            .semantics {
                                this.toggleableState = ToggleableState(checked())
                            },
                ) { content() }
            }

            else -> {
                content()
            }
        }
    }

    private fun ToggleableState(value: Boolean?) = when (value) {
        true -> ToggleableState.On
        false -> ToggleableState.Off
        null -> ToggleableState.Indeterminate
    }

    companion object {
        @Composable
        fun RestrictionsProviderFactory.RestrictedSwitchWrapper(
            model: SwitchPreferenceModel,
            restrictions: Restrictions,
            content: @Composable (SwitchPreferenceModel) -> Unit,
        ) {
            val context = LocalContext.current
            val restrictedMode = rememberRestrictedMode(restrictions).value
            val restrictedSwitchPreferenceModel = remember(restrictedMode) {
                RestrictedSwitchPreferenceModel(context, model, restrictedMode)
            }
            restrictedSwitchPreferenceModel.RestrictionWrapper {
                content(restrictedSwitchPreferenceModel)
            }
        }

        fun getSummary(
            context: Context,
            restrictedModeSupplier: () -> RestrictedMode?,
            summaryIfNoRestricted: () -> String,
            checked: () -> Boolean?,
        ): () -> String = {
            when (val restrictedMode = restrictedModeSupplier()) {
                is NoRestricted -> summaryIfNoRestricted()
                is BaseUserRestricted ->
                    context.getString(com.android.settingslib.R.string.disabled)

                is BlockedByAdmin -> restrictedMode.getSummary(checked())
                is BlockedByEcm ->
                    context.getString(com.android.settingslib.R.string.disabled)

                null -> context.getPlaceholder()
            }
        }
    }
}
