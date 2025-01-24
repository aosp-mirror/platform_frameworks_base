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

package com.android.systemui.volume.dialog.sliders.ui.viewmodel

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.util.time.systemClock
import com.android.systemui.volume.dialog.domain.interactor.volumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.volumeDialogLogger
import com.android.systemui.volume.dialog.sliders.domain.interactor.volumeDialogSliderInputEventsInteractor
import com.android.systemui.volume.dialog.sliders.domain.interactor.volumeDialogSliderInteractor
import com.android.systemui.volume.dialog.sliders.domain.model.volumeDialogSliderType

val Kosmos.volumeDialogSliderViewModel by
    Kosmos.Fixture {
        VolumeDialogSliderViewModel(
            sliderType = volumeDialogSliderType,
            interactor = volumeDialogSliderInteractor,
            inputEventsInteractor = volumeDialogSliderInputEventsInteractor,
            visibilityInteractor = volumeDialogVisibilityInteractor,
            coroutineScope = applicationCoroutineScope,
            volumeDialogSliderIconProvider = volumeDialogSliderIconProvider,
            systemClock = systemClock,
            logger = volumeDialogLogger,
        )
    }
