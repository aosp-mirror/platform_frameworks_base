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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.enterprise.BaseUserRestricted
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdmin
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.model.enterprise.RestrictedMode
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProvider

@Composable
fun RestrictedSwitchPreference(model: SwitchPreferenceModel, restrictions: Restrictions) {
    if (restrictions.keys.isEmpty()) {
        SwitchPreference(model)
        return
    }
    val context = LocalContext.current
    val restrictionsProvider = remember { RestrictionsProvider(context, restrictions) }
    val restrictedMode = restrictionsProvider.restrictedMode.observeAsState().value ?: return
    val restrictedSwitchModel = remember(restrictedMode) {
        RestrictedSwitchPreferenceModel(context, model, restrictedMode)
    }
    Box(remember { restrictedSwitchModel.getModifier() }) {
        SwitchPreference(restrictedSwitchModel)
    }
}

private class RestrictedSwitchPreferenceModel(
    private val context: Context,
    model: SwitchPreferenceModel,
    private val restrictedMode: RestrictedMode,
) : SwitchPreferenceModel {
    override val title = model.title

    override val summary = when (restrictedMode) {
        is NoRestricted -> model.summary
        is BaseUserRestricted -> stateOf(context.getString(R.string.disabled))
        is BlockedByAdmin -> derivedStateOf { restrictedMode.getSummary(model.checked.value) }
    }

    override val checked = when (restrictedMode) {
        is NoRestricted -> model.checked
        is BaseUserRestricted -> stateOf(false)
        is BlockedByAdmin -> model.checked
    }

    override val changeable = when (restrictedMode) {
        is NoRestricted -> model.changeable
        is BaseUserRestricted -> stateOf(false)
        is BlockedByAdmin -> stateOf(false)
    }

    override val onCheckedChange = when (restrictedMode) {
        is NoRestricted -> model.onCheckedChange
        is BaseUserRestricted -> null
        is BlockedByAdmin -> null
    }

    fun getModifier(): Modifier = when (restrictedMode) {
        is BlockedByAdmin -> Modifier.clickable(role = Role.Switch) {
            RestrictedLockUtils.sendShowAdminSupportDetailsIntent(
                context, restrictedMode.enforcedAdmin
            )
        }
        else -> Modifier
    }
}
