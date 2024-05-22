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

package com.android.systemui.volume.panel.component.volume.ui.composable

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformSliderDefaults
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderViewModel
import com.android.systemui.volume.panel.component.volume.ui.viewmodel.AudioVolumeComponentViewModel
import com.android.systemui.volume.panel.component.volume.ui.viewmodel.SlidersExpandableViewModel
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import com.android.systemui.volume.panel.ui.composable.isPortrait
import javax.inject.Inject

class VolumeSlidersComponent
@Inject
constructor(
    private val viewModel: AudioVolumeComponentViewModel,
) : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val sliderViewModels: List<SliderViewModel> by
            viewModel.sliderViewModels.collectAsStateWithLifecycle()
        if (sliderViewModels.isEmpty()) {
            return
        }
        if (isLargeScreen) {
            GridVolumeSliders(
                viewModels = sliderViewModels,
                sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
                modifier = modifier.fillMaxWidth(),
            )
        } else {
            val expandableViewModel: SlidersExpandableViewModel by
                viewModel
                    .isExpandable(isPortrait)
                    .collectAsStateWithLifecycle(SlidersExpandableViewModel.Unavailable)
            if (expandableViewModel is SlidersExpandableViewModel.Unavailable) {
                return
            }
            val isExpanded =
                (expandableViewModel as? SlidersExpandableViewModel.Expandable)?.isExpanded ?: true
            ColumnVolumeSliders(
                viewModels = sliderViewModels,
                isExpanded = isExpanded,
                onExpandedChanged = viewModel::onExpandedChanged,
                sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
                isExpandable = expandableViewModel is SlidersExpandableViewModel.Expandable,
                modifier = modifier.fillMaxWidth(),
            )
        }
    }
}
