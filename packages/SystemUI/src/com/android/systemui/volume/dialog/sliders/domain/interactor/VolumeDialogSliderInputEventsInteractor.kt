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

package com.android.systemui.volume.dialog.sliders.domain.interactor

import android.view.MotionEvent
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogCallbacksInteractor
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.domain.model.VolumeDialogEventModel
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.data.repository.VolumeDialogSliderTouchEventsRepository
import com.android.systemui.volume.dialog.sliders.shared.model.SliderInputEvent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

@VolumeDialogSliderScope
class VolumeDialogSliderInputEventsInteractor
@Inject
constructor(
    @VolumeDialog coroutineScope: CoroutineScope,
    volumeDialogCallbacksInteractor: VolumeDialogCallbacksInteractor,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
    private val repository: VolumeDialogSliderTouchEventsRepository,
) {

    val event: Flow<SliderInputEvent> =
        merge(
            repository.sliderTouchEvent.map { SliderInputEvent.Touch(it) },
            volumeDialogCallbacksInteractor.event
                .filterIsInstance(VolumeDialogEventModel.VolumeChangedFromKey::class)
                .map { SliderInputEvent.Button },
        )

    init {
        event.onEach { visibilityInteractor.resetDismissTimeout() }.launchIn(coroutineScope)
    }

    fun onTouchEvent(newEvent: MotionEvent) {
        repository.update(newEvent)
    }
}
