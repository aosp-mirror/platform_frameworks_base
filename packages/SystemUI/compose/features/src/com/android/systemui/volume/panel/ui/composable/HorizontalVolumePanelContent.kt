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

package com.android.systemui.volume.panel.ui.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.systemui.volume.panel.ui.layout.ComponentsLayout

@Composable
fun VolumePanelComposeScope.HorizontalVolumePanelContent(
    layout: ComponentsLayout,
    modifier: Modifier = Modifier,
) {
    val spacing = 20.dp
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(space = spacing)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            for (component in layout.contentComponents) {
                AnimatedVisibility(component.isVisible) {
                    with(component.component as ComposeVolumePanelUiComponent) { Content(Modifier) }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(space = spacing, alignment = Alignment.Top)
        ) {
            for (component in layout.headerComponents) {
                AnimatedVisibility(visible = component.isVisible) {
                    with(component.component as ComposeVolumePanelUiComponent) { Content(Modifier) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                for (component in layout.footerComponents) {
                    AnimatedVisibility(
                        visible = component.isVisible,
                        modifier = Modifier.weight(1f),
                    ) {
                        with(component.component as ComposeVolumePanelUiComponent) {
                            Content(Modifier)
                        }
                    }
                }
            }
        }
    }
}
