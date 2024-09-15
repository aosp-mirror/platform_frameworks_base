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

package com.android.systemui.settings.brightness.ui.viewModel

import android.content.res.Resources
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.settings.brightness.BrightnessSliderController
import com.android.systemui.settings.brightness.MirrorController
import com.android.systemui.settings.brightness.ToggleSlider
import com.android.systemui.settings.brightness.domain.interactor.BrightnessMirrorShowingInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class BrightnessMirrorViewModel
@Inject
constructor(
    private val brightnessMirrorShowingInteractor: BrightnessMirrorShowingInteractor,
    @Main private val resources: Resources,
    val sliderControllerFactory: BrightnessSliderController.Factory,
) : MirrorController {

    private val tempPosition = IntArray(2)

    private var _toggleSlider: BrightnessSliderController? = null

    val isShowing = brightnessMirrorShowingInteractor.isShowing

    private val _locationAndSize: MutableStateFlow<LocationAndSize> =
        MutableStateFlow(LocationAndSize())
    val locationAndSize = _locationAndSize.asStateFlow()

    override fun getToggleSlider(): ToggleSlider? {
        return _toggleSlider
    }

    fun setToggleSlider(toggleSlider: BrightnessSliderController) {
        _toggleSlider = toggleSlider
    }

    override fun showMirror() {
        brightnessMirrorShowingInteractor.setMirrorShowing(true)
    }

    override fun hideMirror() {
        brightnessMirrorShowingInteractor.setMirrorShowing(false)
    }

    override fun setLocationAndSize(view: View) {
        view.getLocationInWindow(tempPosition)
        val padding = resources.getDimensionPixelSize(R.dimen.rounded_slider_background_padding)
        _toggleSlider?.rootView?.setPadding(padding, padding, padding, padding)
        // Account for desired padding
        _locationAndSize.value =
            LocationAndSize(
                yOffset = tempPosition[1] - padding,
                width = view.measuredWidth + 2 * padding,
                height = view.measuredHeight + 2 * padding,
            )
    }

    // Callbacks are used for indicating reinflation when the config changes in some ways (like
    // density). However, we don't need that as we recompose the view anyway
    override fun addCallback(listener: MirrorController.BrightnessMirrorListener) {}

    override fun removeCallback(listener: MirrorController.BrightnessMirrorListener) {}
}

data class LocationAndSize(
    val yOffset: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)
