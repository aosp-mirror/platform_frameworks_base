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

package com.android.systemui.volume.dialog.sliders.dagger

import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderHapticsViewBinder
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderTouchesViewBinder
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderViewBinder
import dagger.BindsInstance
import dagger.Subcomponent

/**
 * This component hosts all the stuff, that Volume Dialog sliders need. It's recreated alongside
 * each slider view.
 */
@VolumeDialogSliderScope
@Subcomponent
interface VolumeDialogSliderComponent {

    fun sliderViewBinder(): VolumeDialogSliderViewBinder

    fun sliderTouchesViewBinder(): VolumeDialogSliderTouchesViewBinder

    fun sliderHapticsViewBinder(): VolumeDialogSliderHapticsViewBinder

    @Subcomponent.Factory
    interface Factory {

        fun create(@BindsInstance sliderType: VolumeDialogSliderType): VolumeDialogSliderComponent
    }
}
