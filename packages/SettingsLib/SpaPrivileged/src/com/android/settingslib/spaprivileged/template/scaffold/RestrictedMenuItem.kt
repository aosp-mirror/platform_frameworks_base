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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spa.widget.scaffold.MoreOptionsScope
import com.android.settingslib.spaprivileged.model.enterprise.BaseUserRestricted
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdmin
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl

@Composable
fun MoreOptionsScope.RestrictedMenuItem(
    text: String,
    restrictions: Restrictions,
    onClick: () -> Unit,
) {
    RestrictedMenuItemImpl(text, restrictions, onClick, ::RestrictionsProviderImpl)
}

@Composable
internal fun MoreOptionsScope.RestrictedMenuItemImpl(
    text: String,
    restrictions: Restrictions,
    onClick: () -> Unit,
    restrictionsProviderFactory: RestrictionsProviderFactory,
) {
    val context = LocalContext.current
    val restrictionsProvider = remember(restrictions) {
        restrictionsProviderFactory(context, restrictions)
    }
    val restrictedMode = restrictionsProvider.restrictedModeState().value
    MenuItem(text = text, enabled = restrictedMode !is BaseUserRestricted) {
        when (restrictedMode) {
            is BlockedByAdmin -> restrictedMode.sendShowAdminSupportDetailsIntent()
            else -> onClick()
        }
    }
}
