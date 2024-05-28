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

package com.android.settingslib.spaprivileged.template.scaffold

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.widget.scaffold.MoreOptionsScope
import com.android.settingslib.spaprivileged.model.enterprise.BaseUserRestricted
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdmin
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByEcm
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl
import com.android.settingslib.spaprivileged.model.enterprise.rememberRestrictedMode

@Composable
fun MoreOptionsScope.RestrictedMenuItem(
    text: String,
    enabled: Boolean = true,
    restrictions: Restrictions,
    onClick: () -> Unit,
) {
    RestrictedMenuItemImpl(text, enabled, restrictions, onClick, ::RestrictionsProviderImpl)
}

@VisibleForTesting
@Composable
internal fun MoreOptionsScope.RestrictedMenuItemImpl(
    text: String,
    enabled: Boolean = true,
    restrictions: Restrictions,
    onClick: () -> Unit,
    restrictionsProviderFactory: RestrictionsProviderFactory,
) {
    val restrictedMode = restrictionsProviderFactory.rememberRestrictedMode(restrictions).value
    MenuItem(text = text, enabled = enabled && restrictedMode !== BaseUserRestricted) {
        when (restrictedMode) {
            is BlockedByAdmin -> restrictedMode.sendShowAdminSupportDetailsIntent()
            is BlockedByEcm -> restrictedMode.showRestrictedSettingsDetails()
            else -> onClick()
        }
    }
}
