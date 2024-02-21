/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.panel.component.button.ui.composable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.volume.panel.component.button.ui.viewmodel.ToggleButtonViewModel
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import kotlinx.coroutines.flow.StateFlow

class ToggleButtonComponent(
    private val viewModelFlow: StateFlow<ToggleButtonViewModel?>,
    private val onCheckedChange: (isChecked: Boolean) -> Unit
) : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val viewModelByState by viewModelFlow.collectAsState()
        val viewModel = viewModelByState ?: return
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedIconToggleButton(
                modifier = Modifier.height(64.dp).fillMaxWidth(),
                checked = viewModel.isChecked,
                onCheckedChange = onCheckedChange,
                colors =
                    IconButtonDefaults.outlinedIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                border = BorderStroke(8.dp, MaterialTheme.colorScheme.surface),
            ) {
                Icon(modifier = Modifier.size(24.dp), icon = viewModel.icon)
            }
            Text(
                text = viewModel.label.toString(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
