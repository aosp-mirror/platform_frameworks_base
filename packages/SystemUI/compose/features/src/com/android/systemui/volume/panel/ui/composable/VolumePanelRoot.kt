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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import com.android.compose.theme.PlatformTheme
import com.android.systemui.res.R
import com.android.systemui.volume.panel.ui.layout.ComponentsLayout
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelState
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel

@Composable
fun VolumePanelRoot(
    viewModel: VolumePanelViewModel,
    onDismissAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformTheme(isSystemInDarkTheme()) {
        val state: VolumePanelState by viewModel.volumePanelState.collectAsState()
        val components by viewModel.componentsLayout.collectAsState(null)

        val transitionState =
            remember { MutableTransitionState(false) }.apply { targetState = state.isVisible }

        LaunchedEffect(transitionState.targetState, transitionState.isIdle) {
            if (!transitionState.targetState && transitionState.isIdle) {
                onDismissAnimationFinished()
            }
        }

        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .clickable(onClick = { viewModel.dismissPanel() }),
            verticalArrangement = Arrangement.Bottom,
        ) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                val radius = dimensionResource(R.dimen.volume_panel_corner_radius)
                Surface(
                    shape = RoundedCornerShape(topStart = radius, topEnd = radius),
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    Column {
                        components?.let { componentsState ->
                            with(VolumePanelComposeScope(state)) { Components(componentsState) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumePanelComposeScope.Components(state: ComponentsLayout) {
    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
        VerticalVolumePanelContent(
            components = state.contentComponents,
            modifier = Modifier.padding(dimensionResource(R.dimen.volume_panel_content_padding)),
        )
    } else {
        TODO("Add landscape layout")
    }

    val horizontalPadding = dimensionResource(R.dimen.volume_panel_bottom_bar_horizontal_padding)
    if (state.bottomBarComponent.isVisible) {
        with(state.bottomBarComponent.component as ComposeVolumePanelUiComponent) {
            Content(
                Modifier.navigationBarsPadding()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        bottom = dimensionResource(R.dimen.volume_panel_bottom_bar_bottom_padding),
                    )
            )
        }
    }
}
