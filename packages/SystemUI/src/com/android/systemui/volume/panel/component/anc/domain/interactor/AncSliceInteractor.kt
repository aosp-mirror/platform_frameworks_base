/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.panel.component.anc.domain.interactor

import android.app.slice.Slice.HINT_ERROR
import android.app.slice.SliceItem.FORMAT_SLICE
import androidx.slice.Slice
import com.android.systemui.volume.domain.interactor.AudioOutputInteractor
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.panel.component.anc.data.repository.AncSliceRepository
import com.android.systemui.volume.panel.component.anc.domain.model.AncSlices
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Provides a valid slice from [AncSliceRepository]. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class AncSliceInteractor
@Inject
constructor(
    private val audioOutputInteractor: AudioOutputInteractor,
    private val ancSliceRepository: AncSliceRepository,
    scope: CoroutineScope,
) {

    // Any positive width to check if the Slice is available.
    private val buttonSliceWidth = MutableStateFlow(1)
    private val popupSliceWidth = MutableStateFlow(1)

    val ancSlices: StateFlow<AncSlices> =
        combine(
                buttonSliceWidth.flatMapLatest {
                    ancSlice(width = it, isCollapsed = true, hideLabel = true)
                },
                popupSliceWidth.flatMapLatest {
                    ancSlice(width = it, isCollapsed = false, hideLabel = false)
                }
            ) { buttonSlice, popupSlice ->
                if (buttonSlice != null && popupSlice != null) {
                    AncSlices.Ready(buttonSlice = buttonSlice, popupSlice = popupSlice)
                } else {
                    AncSlices.Unavailable
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, AncSlices.Unavailable)

    /**
     * Provides a valid [isCollapsed] ANC slice for a given [width]. Use [hideLabel] == true to
     * remove the labels from the [Slice].
     */
    private fun ancSlice(width: Int, isCollapsed: Boolean, hideLabel: Boolean): Flow<Slice?> {
        return audioOutputInteractor.currentAudioDevice.flatMapLatest { outputDevice ->
            if (outputDevice is AudioOutputDevice.Bluetooth) {
                ancSliceRepository
                    .ancSlice(
                        device = outputDevice.cachedBluetoothDevice.device,
                        width = width,
                        isCollapsed = isCollapsed,
                        hideLabel = hideLabel,
                    )
                    .filter { it?.isValidSlice() != false }
            } else {
                flowOf(null)
            }
        }
    }

    private fun Slice.isValidSlice(): Boolean {
        if (hints.contains(HINT_ERROR)) {
            return false
        }
        for (item in items) {
            if (item.format == FORMAT_SLICE) {
                return true
            }
        }
        return false
    }

    /**
     * Call this to update [AncSlices.Ready.popupSlice] width in a reaction to container size
     * change.
     */
    fun onPopupSliceWidthChanged(width: Int) {
        popupSliceWidth.tryEmit(width)
    }

    /**
     * Call this to update [AncSlices.Ready.buttonSlice] width in a reaction to container size
     * change.
     */
    fun onButtonSliceWidthChanged(width: Int) {
        buttonSliceWidth.tryEmit(width)
    }
}
