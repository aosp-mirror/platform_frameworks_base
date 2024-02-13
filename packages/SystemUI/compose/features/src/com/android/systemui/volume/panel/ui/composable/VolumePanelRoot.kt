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

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
                    Column { components?.let { componentsState -> Components(componentsState) } }
                }
            }
        }
    }
}

@Composable
private fun VolumePanelComposeScope.Components(components: ComponentsLayout) {
    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
        VerticalVolumePanelContent(
            components,
            modifier = Modifier.padding(24.dp),
        )
    } else {
        HorizontalVolumePanelContent(
            components,
            modifier =
                Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 20.dp)
                    .heightIn(max = 236.dp),
        )
    }

    if (components.bottomBarComponent.isVisible) {
        val horizontalPadding =
            dimensionResource(R.dimen.volume_panel_bottom_bar_horizontal_padding)
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        bottom = dimensionResource(R.dimen.volume_panel_bottom_bar_bottom_padding),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            with(components.bottomBarComponent.component as ComposeVolumePanelUiComponent) {
                Content(Modifier)
            }
        }
    }
}
