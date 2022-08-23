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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.framework.compose.LocalNavController

@Composable
internal fun NavigateUp() {
    val navController = LocalNavController.current
    val contentDescription = stringResource(
        id = androidx.appcompat.R.string.abc_action_bar_up_description,
    )
    BackAction(contentDescription) {
        navController.navigateUp()
    }
}

@Composable
private fun BackAction(contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(
            imageVector = Icons.Outlined.ArrowBack,
            contentDescription = contentDescription,
        )
    }
}
