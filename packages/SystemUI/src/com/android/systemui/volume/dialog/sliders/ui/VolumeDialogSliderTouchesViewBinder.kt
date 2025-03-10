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

import android.annotation.SuppressLint
import android.view.View
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderInputEventsViewModel
import com.google.android.material.slider.Slider
import javax.inject.Inject

@VolumeDialogSliderScope
class VolumeDialogSliderTouchesViewBinder
@Inject
constructor(private val viewModel: VolumeDialogSliderInputEventsViewModel) {

    @SuppressLint("ClickableViewAccessibility")
    fun bind(view: View) {
        with(view.requireViewById<Slider>(R.id.volume_dialog_slider)) {
            setOnTouchListener { _, event ->
                viewModel.onTouchEvent(event)
                false
            }
        }
    }
}
