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

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.volume.panel.component.button.ui.viewmodel.ButtonViewModel
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import kotlinx.coroutines.flow.StateFlow

/** [ComposeVolumePanelUiComponent] implementing a toggleable button from a bottom row. */
class ToggleButtonComponent(
    private val viewModelFlow: StateFlow<ButtonViewModel?>,
    private val onCheckedChange: (isChecked: Boolean) -> Unit
) : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val viewModelByState by viewModelFlow.collectAsState()
        val viewModel = viewModelByState ?: return
        val label = viewModel.label.toString()

        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BottomComponentButtonSurface {
                val colors =
                    if (viewModel.isActive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                Button(
                    modifier =
                        Modifier.fillMaxSize().padding(8.dp).semantics {
                            role = Role.Switch
                            toggleableState =
                                if (viewModel.isActive) {
                                    ToggleableState.On
                                } else {
                                    ToggleableState.Off
                                }
                            contentDescription = label
                        },
                    onClick = { onCheckedChange(!viewModel.isActive) },
                    shape = RoundedCornerShape(28.dp),
                    colors = colors,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(modifier = Modifier.size(24.dp), icon = viewModel.icon)
                }
            }

            Text(
                modifier = Modifier.clearAndSetSemantics {}.basicMarquee(),
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
            )
        }
    }
}
