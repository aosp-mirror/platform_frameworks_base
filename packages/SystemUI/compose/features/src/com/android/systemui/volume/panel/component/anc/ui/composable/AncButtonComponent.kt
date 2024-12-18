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

package com.android.systemui.volume.panel.component.anc.ui.composable

import android.view.Gravity
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.anc.ui.viewmodel.AncViewModel
import com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopup
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import javax.inject.Inject

class AncButtonComponent
@Inject
constructor(
    private val viewModel: AncViewModel,
    private val ancPopup: AncPopup,
) : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val slice by viewModel.buttonSlice.collectAsStateWithLifecycle()
        val label = stringResource(R.string.volume_panel_noise_control_title)
        val screenWidth: Float =
            with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        var gravity by remember { mutableIntStateOf(Gravity.CENTER_HORIZONTAL) }
        val isClickable = viewModel.isClickable(slice)
        Column(
            modifier =
                modifier.onGloballyPositioned {
                    gravity = VolumePanelPopup.calculateGravity(it, screenWidth)
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.height(64.dp),
            ) {
                SliceAndroidView(
                    modifier = modifier.fillMaxSize(),
                    slice = slice,
                    onWidthChanged = viewModel::onButtonSliceWidthChanged,
                    enableAccessibility = false,
                )
                Button(
                    modifier =
                        modifier.fillMaxSize().padding(8.dp).semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = label
                        },
                    enabled = isClickable,
                    onClick = { with(ancPopup) { show(null, gravity) } },
                    colors =
                        ButtonColors(
                            contentColor = Color.Transparent,
                            containerColor = Color.Transparent,
                            disabledContentColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        )
                ) {}
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
