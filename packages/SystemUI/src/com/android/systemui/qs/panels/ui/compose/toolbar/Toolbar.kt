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

package com.android.systemui.qs.panels.ui.compose.toolbar

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.footer.ui.compose.IconButton
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel

@Composable
fun Toolbar(toolbarViewModelFactory: ToolbarViewModel.Factory, modifier: Modifier = Modifier) {
    val viewModel = rememberViewModel("Toolbar") { toolbarViewModelFactory.create() }

    Row(
        modifier = modifier.fillMaxWidth().requiredHeight(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        viewModel.userSwitcherViewModel?.let {
            IconButton(it, Modifier.sysuiResTag("multi_user_switch"))
        }

        EditModeButton(viewModel.editModeButtonViewModelFactory)

        IconButton(
            viewModel.settingsButtonViewModel,
            Modifier.sysuiResTag("settings_button_container"),
        )

        Spacer(modifier = Modifier.weight(1f))
        IconButton(viewModel.powerButtonViewModel, Modifier.sysuiResTag("pm_lite"))
    }
}
