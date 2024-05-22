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

import android.view.Gravity
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.volume.panel.component.button.ui.viewmodel.ButtonViewModel
import com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopup.Companion.calculateGravity
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import kotlinx.coroutines.flow.StateFlow

/** [ComposeVolumePanelUiComponent] implementing a clickable button from a bottom row. */
class ButtonComponent(
    private val viewModelFlow: StateFlow<ButtonViewModel?>,
    private val onClick: (expandable: Expandable, horizontalGravity: Int) -> Unit
) : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val viewModelByState by viewModelFlow.collectAsStateWithLifecycle()
        val viewModel = viewModelByState ?: return
        val label = viewModel.label.toString()

        val screenWidth: Float =
            with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        var gravity by remember { mutableIntStateOf(Gravity.CENTER_HORIZONTAL) }

        Column(
            modifier =
                modifier.onGloballyPositioned { gravity = calculateGravity(it, screenWidth) },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BottomComponentButtonSurface {
                Expandable(
                    modifier =
                        Modifier.fillMaxSize().padding(8.dp).semantics {
                            role = Role.Button
                            contentDescription = label
                        },
                    color =
                        if (viewModel.isActive) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    shape = RoundedCornerShape(28.dp),
                    contentColor =
                        if (viewModel.isActive) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    onClick = { onClick(it, gravity) },
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(modifier = Modifier.size(24.dp), icon = viewModel.icon)
                    }
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
