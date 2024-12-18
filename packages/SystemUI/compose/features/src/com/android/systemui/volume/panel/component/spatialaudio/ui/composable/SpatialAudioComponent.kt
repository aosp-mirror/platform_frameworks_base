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

package com.android.systemui.volume.panel.component.spatialaudio.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.volume.panel.component.button.ui.composable.ButtonComponent
import com.android.systemui.volume.panel.component.button.ui.composable.ToggleButtonComponent
import com.android.systemui.volume.panel.component.spatial.domain.model.SpatialAudioEnabledModel
import com.android.systemui.volume.panel.component.spatial.ui.viewmodel.SpatialAudioViewModel
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import javax.inject.Inject

/** [ComposeVolumePanelUiComponent] that represents spatial audio button in the Volume Panel. */
class SpatialAudioComponent
@Inject
constructor(
    private val viewModel: SpatialAudioViewModel,
    private val popup: SpatialAudioPopup,
) : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val shouldUsePopup by viewModel.shouldUsePopup.collectAsStateWithLifecycle()

        val buttonComponent: ComposeVolumePanelUiComponent =
            remember(shouldUsePopup) {
                if (shouldUsePopup) {
                    ButtonComponent(viewModel.spatialAudioButton, popup::show)
                } else {
                    ToggleButtonComponent(viewModel.spatialAudioButton) {
                        if (it) {
                            viewModel.setEnabled(SpatialAudioEnabledModel.SpatialAudioEnabled)
                        } else {
                            viewModel.setEnabled(SpatialAudioEnabledModel.Disabled)
                        }
                    }
                }
            }
        with(buttonComponent) { Content(modifier) }
    }
}
