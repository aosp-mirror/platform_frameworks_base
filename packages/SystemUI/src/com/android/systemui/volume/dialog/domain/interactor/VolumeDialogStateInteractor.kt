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

package com.android.systemui.volume.dialog.domain.interactor

import android.util.SparseArray
import androidx.core.util.keyIterator
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPlugin
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.data.repository.VolumeDialogStateRepository
import com.android.systemui.volume.dialog.domain.model.VolumeDialogEventModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogSafetyWarningModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStateModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

/**
 * Exposes [VolumeDialogController.getState] in the [volumeDialogState].
 *
 * @see [VolumeDialogController]
 */
@VolumeDialogPluginScope
class VolumeDialogStateInteractor
@Inject
constructor(
    volumeDialogCallbacksInteractor: VolumeDialogCallbacksInteractor,
    private val volumeDialogController: VolumeDialogController,
    private val volumeDialogStateRepository: VolumeDialogStateRepository,
    @VolumeDialogPlugin private val coroutineScope: CoroutineScope,
) {

    init {
        volumeDialogCallbacksInteractor.event
            .onEach { event ->
                when (event) {
                    is VolumeDialogEventModel.StateChanged -> {
                        volumeDialogStateRepository.updateState { oldState ->
                            event.state.copyIntoModel(oldState)
                        }
                    }
                    is VolumeDialogEventModel.AccessibilityModeChanged -> {
                        volumeDialogStateRepository.updateState { oldState ->
                            oldState.copy(shouldShowA11ySlider = event.showA11yStream)
                        }
                    }
                    is VolumeDialogEventModel.ShowSafetyWarning -> {
                        setSafetyWarning(VolumeDialogSafetyWarningModel.Visible(event.flags))
                    }
                    else -> {
                        // do nothing
                    }
                }
            }
            .onStart { volumeDialogController.getState() }
            .launchIn(coroutineScope)
    }

    val volumeDialogState: Flow<VolumeDialogStateModel> = volumeDialogStateRepository.state

    fun setSafetyWarning(model: VolumeDialogSafetyWarningModel) {
        volumeDialogStateRepository.updateState { it.copy(isShowingSafetyWarning = model) }
    }

    /** Returns a copy of [model] filled with the values from [VolumeDialogController.State]. */
    private fun VolumeDialogController.State.copyIntoModel(
        model: VolumeDialogStateModel
    ): VolumeDialogStateModel {
        return model.copy(
            streamModels =
                states.mapToMap { stream, streamState ->
                    VolumeDialogStreamModel(
                        stream = stream,
                        isActive = stream == activeStream,
                        legacyState = streamState,
                    )
                },
            ringerModeInternal = ringerModeInternal,
            ringerModeExternal = ringerModeExternal,
            zenMode = zenMode,
            effectsSuppressor = effectsSuppressor,
            effectsSuppressorName = effectsSuppressorName,
            activeStream = activeStream,
            disallowAlarms = disallowAlarms,
            disallowMedia = disallowMedia,
            disallowSystem = disallowSystem,
            disallowRinger = disallowRinger,
        )
    }
}

private fun <INPUT, OUTPUT> SparseArray<INPUT>.mapToMap(
    map: (Int, INPUT) -> OUTPUT
): Map<Int, OUTPUT> {
    val resultMap = mutableMapOf<Int, OUTPUT>()
    for (key in keyIterator()) {
        val mappedValue: OUTPUT = map(key, get(key)!!)
        resultMap[key] = mappedValue
    }
    return resultMap
}
