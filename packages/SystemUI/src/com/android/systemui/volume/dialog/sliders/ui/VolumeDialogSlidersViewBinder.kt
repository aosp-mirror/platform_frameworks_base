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

package com.android.systemui.volume.dialog.sliders.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.compose.ui.util.fastForEachIndexed
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderComponent
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSlidersViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@VolumeDialogScope
class VolumeDialogSlidersViewBinder
@Inject
constructor(private val viewModelFactory: VolumeDialogSlidersViewModel.Factory) {

    fun bind(view: View) {
        with(view) {
            val floatingSlidersContainer: ViewGroup =
                requireViewById(R.id.volume_dialog_floating_sliders_container)
            val mainSliderContainer: View =
                requireViewById(R.id.volume_dialog_main_slider_container)
            repeatWhenAttached {
                viewModel(
                    traceName = "VolumeDialogSlidersViewBinder",
                    minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                    factory = { viewModelFactory.create() },
                ) { viewModel ->
                    viewModel.sliders
                        .onEach { uiModel ->
                            uiModel.sliderComponent.bindSlider(mainSliderContainer)

                            val floatingSliderViewBinders = uiModel.floatingSliderComponent
                            floatingSlidersContainer.ensureChildCount(
                                viewLayoutId = R.layout.volume_dialog_slider_floating,
                                count = floatingSliderViewBinders.size,
                            )
                            floatingSliderViewBinders.fastForEachIndexed { index, sliderComponent ->
                                sliderComponent.bindSlider(
                                    floatingSlidersContainer.getChildAt(index)
                                )
                            }
                        }
                        .launchIn(this)
                }
            }
        }
    }

    private fun VolumeDialogSliderComponent.bindSlider(sliderContainer: View) {
        sliderViewBinder().bind(sliderContainer)
        sliderTouchesViewBinder().bind(sliderContainer)
    }
}

private fun ViewGroup.ensureChildCount(@LayoutRes viewLayoutId: Int, count: Int) {
    val childCountDelta = childCount - count
    when {
        childCountDelta > 0 -> {
            removeViews(0, childCountDelta)
        }
        childCountDelta < 0 -> {
            val inflater = LayoutInflater.from(context)
            repeat(-childCountDelta) { inflater.inflate(viewLayoutId, this, true) }
        }
    }
}
