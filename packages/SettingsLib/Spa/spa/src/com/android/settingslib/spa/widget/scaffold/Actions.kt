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

package com.android.settingslib.spa.widget.scaffold

import androidx.appcompat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled

/** Action that navigates back to last page. */
@Composable
internal fun NavigateBack() {
    val navController = LocalNavController.current
    val contentDescription = stringResource(R.string.abc_action_bar_up_description)
    BackAction(contentDescription) {
        navController.navigateBack()
    }
}

/** Action that collapses the search bar. */
@Composable
internal fun CollapseAction(onClick: () -> Unit) {
    val contentDescription = stringResource(R.string.abc_toolbar_collapse_description)
    BackAction(contentDescription, onClick)
}

@Composable
private fun BackAction(contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = contentDescription,
            modifier = if (isSpaExpressiveEnabled) Modifier
                .size(SettingsDimension.actionIconWidth, SettingsDimension.actionIconHeight)
                .clip(SettingsShape.CornerExtraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(SettingsDimension.actionIconPadding) else Modifier
        )
    }
}

/** Action that expends the search bar. */
@Composable
internal fun SearchAction(onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(
            imageVector = Icons.Outlined.FindInPage,
            contentDescription = stringResource(R.string.search_menu_title),
        )
    }
}

/** Action that clear the search query. */
@Composable
internal fun ClearAction(onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(
            imageVector = Icons.Outlined.Clear,
            contentDescription = stringResource(R.string.abc_searchview_description_clear),
        )
    }
}
