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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.res.R
import com.android.systemui.volume.panel.ui.layout.ComponentsLayout
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelState
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel

/** Same as android.platform.systemui_tapl.ui.VolumePanel#VolumePanelTestTag */
private const val VolumePanelTestTag = "VolumePanel"
private val padding = 24.dp

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun VolumePanelRoot(
    viewModel: VolumePanelViewModel,
    modifier: Modifier = Modifier,
) {
    val accessibilityTitle = stringResource(R.string.accessibility_volume_settings)
    val state: VolumePanelState by viewModel.volumePanelState.collectAsStateWithLifecycle()
    val components by viewModel.componentsLayout.collectAsStateWithLifecycle(null)

    with(VolumePanelComposeScope(state)) {
        components?.let { componentsState ->
            Components(
                componentsState,
                modifier
                    .sysuiResTag(VolumePanelTestTag)
                    .semantics { paneTitle = accessibilityTitle }
                    .padding(
                        start = padding,
                        top = padding,
                        end = padding,
                        bottom = 20.dp,
                    )
            )
        }
    }
}

@Composable
private fun VolumePanelComposeScope.Components(
    layout: ComponentsLayout,
    modifier: Modifier = Modifier
) {
    val arrangement: Arrangement.Vertical =
        if (isLargeScreen) {
            Arrangement.spacedBy(20.dp)
        } else {
            if (isPortrait) Arrangement.spacedBy(padding) else Arrangement.spacedBy(4.dp)
        }
    Column(
        modifier = modifier,
        verticalArrangement = arrangement,
    ) {
        if (isPortrait || isLargeScreen) {
            VerticalVolumePanelContent(
                modifier = Modifier.weight(weight = 1f, fill = false),
                layout = layout
            )
        } else {
            HorizontalVolumePanelContent(
                modifier = Modifier.weight(weight = 1f, fill = false).heightIn(max = 212.dp),
                layout = layout,
            )
        }
        BottomBar(
            modifier = Modifier,
            layout = layout,
        )
    }
}

@Composable
private fun VolumePanelComposeScope.BottomBar(
    layout: ComponentsLayout,
    modifier: Modifier = Modifier
) {
    if (layout.bottomBarComponent.isVisible) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            with(layout.bottomBarComponent.component as ComposeVolumePanelUiComponent) {
                Content(Modifier)
            }
        }
    }
}
