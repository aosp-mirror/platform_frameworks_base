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

package com.android.systemui.settings.brightness

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.android.compose.theme.PlatformTheme
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.composable.QuickSettingsShade

object ComposeDialogComposableProvider {

    fun setComposableBrightness(composeView: ComposeView, content: ComposableProvider) {
        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PlatformTheme { content.ProvideComposableContent() } }
        }
    }
}

@Composable
private fun BrightnessSliderForDialog(
    brightnessSliderViewModelFactory: BrightnessSliderViewModel.Factory
) {
    val viewModel =
        rememberViewModel(traceName = "BrightnessDialog.viewModel") {
            brightnessSliderViewModelFactory.create(false)
        }
    BrightnessSliderContainer(
        viewModel = viewModel,
        Modifier.fillMaxWidth().height(QuickSettingsShade.Dimensions.BrightnessSliderHeight),
    )
}

class ComposableProvider(
    private val brightnessSliderViewModelFactory: BrightnessSliderViewModel.Factory
) {
    @Composable
    fun ProvideComposableContent() {
        BrightnessSliderForDialog(brightnessSliderViewModelFactory)
    }
}
