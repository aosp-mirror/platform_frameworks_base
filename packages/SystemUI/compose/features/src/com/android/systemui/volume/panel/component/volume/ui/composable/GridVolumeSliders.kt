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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformSliderColors
import com.android.compose.grid.VerticalGrid
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderViewModel

@Composable
fun GridVolumeSliders(
    viewModels: List<SliderViewModel>,
    sliderColors: PlatformSliderColors,
    modifier: Modifier = Modifier,
) {
    require(viewModels.isNotEmpty())
    VerticalGrid(
        modifier = modifier,
        columns = 2,
        verticalSpacing = 16.dp,
        horizontalSpacing = 24.dp,
    ) {
        for (sliderViewModel in viewModels) {
            val sliderState = sliderViewModel.slider.collectAsState().value
            VolumeSlider(
                modifier = Modifier.fillMaxWidth(),
                state = sliderState,
                onValueChangeFinished = { sliderViewModel.onValueChangeFinished(sliderState, it) },
                sliderColors = sliderColors,
            )
        }
    }
}
