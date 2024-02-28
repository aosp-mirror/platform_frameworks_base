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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import com.android.compose.theme.PlatformTheme
import com.android.systemui.res.R
import com.android.systemui.volume.panel.ui.layout.ComponentsLayout
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelState
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel

private val padding = 24.dp

@Composable
fun VolumePanelRoot(
    viewModel: VolumePanelViewModel,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.volumePanelState.collect {
            if (!it.isVisible) {
                onDismiss()
            }
        }
    }

    PlatformTheme(isSystemInDarkTheme()) {
        val state: VolumePanelState by viewModel.volumePanelState.collectAsState()
        val components by viewModel.componentsLayout.collectAsState(null)

        with(VolumePanelComposeScope(state)) {
            var boxModifier = modifier.fillMaxSize().clickable(onClick = onDismiss)
            if (!isPortrait) {
                boxModifier = boxModifier.padding(horizontal = 48.dp)
            }
            Box(
                modifier = boxModifier,
                contentAlignment = Alignment.BottomCenter,
            ) {
                val radius = dimensionResource(R.dimen.volume_panel_corner_radius)
                Surface(
                    modifier =
                        Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = {
                                // prevent windowCloseOnTouchOutside from dismissing when tapped on
                                // the panel itself.
                            },
                        ),
                    shape = RoundedCornerShape(topStart = radius, topEnd = radius),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    components?.let { componentsState ->
                        Components(
                            componentsState,
                            Modifier.padding(
                                    start = padding,
                                    top = padding,
                                    end = padding,
                                    bottom = 20.dp,
                                )
                                .navigationBarsPadding()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumePanelComposeScope.Components(
    layout: ComponentsLayout,
    modifier: Modifier = Modifier
) {
    val arrangement =
        if (isLargeScreen) {
            Arrangement.spacedBy(20.dp)
        } else {
            if (isPortrait) Arrangement.spacedBy(padding) else Arrangement.spacedBy(4.dp)
        }
    Column(
        modifier = modifier.widthIn(max = 800.dp),
        verticalArrangement = arrangement,
    ) {
        val contentModifier = Modifier
        if (isPortrait || isLargeScreen) {
            VerticalVolumePanelContent(modifier = contentModifier, layout = layout)
        } else {
            HorizontalVolumePanelContent(
                modifier = contentModifier.heightIn(max = 212.dp),
                layout = layout
            )
        }
        BottomBar(layout = layout, modifier = Modifier)
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
