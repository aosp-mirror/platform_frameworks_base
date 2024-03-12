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

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spaprivileged.model.enterprise.BaseUserRestricted
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdmin
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.model.enterprise.RestrictedMode
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl
import com.android.settingslib.spaprivileged.model.enterprise.rememberRestrictedMode

@Composable
fun RestrictedPreference(
    model: PreferenceModel,
    restrictions: Restrictions,
) {
    RestrictedPreference(model, restrictions, ::RestrictionsProviderImpl)
}

@VisibleForTesting
@Composable
internal fun RestrictedPreference(
    model: PreferenceModel,
    restrictions: Restrictions,
    restrictionsProviderFactory: RestrictionsProviderFactory,
) {
    if (restrictions.keys.isEmpty()) {
        Preference(model)
        return
    }
    val restrictedMode = restrictionsProviderFactory.rememberRestrictedMode(restrictions).value
    val restrictedModel = remember(restrictedMode) {
        RestrictedPreferenceModel(model, restrictedMode)
    }
    restrictedModel.RestrictionWrapper {
        Preference(restrictedModel)
    }
}

internal fun RestrictedMode?.restrictEnabled(enabled: () -> Boolean) = when (this) {
    NoRestricted -> enabled
    else -> ({ false })
}

internal fun <T> RestrictedMode?.restrictOnClick(onClick: T): T? = when (this) {
    NoRestricted -> onClick
    // Need to passthrough onClick for clickable semantics, although since enabled is false so
    // this will not be called.
    BaseUserRestricted -> onClick
    else -> null
}

private class RestrictedPreferenceModel(
    model: PreferenceModel,
    private val restrictedMode: RestrictedMode?,
) : PreferenceModel {
    override val title = model.title
    override val summary = model.summary
    override val icon = model.icon
    override val enabled = restrictedMode.restrictEnabled(model.enabled)
    override val onClick = restrictedMode.restrictOnClick(model.onClick)

    @Composable
    fun RestrictionWrapper(content: @Composable () -> Unit) {
        if (restrictedMode !is BlockedByAdmin) {
            content()
            return
        }
        Box(
            Modifier
                .clickable(
                    role = Role.Button,
                    onClick = { restrictedMode.sendShowAdminSupportDetailsIntent() },
                )
        ) { content() }
    }
}
