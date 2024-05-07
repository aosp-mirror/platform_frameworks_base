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

package com.android.systemui.volume.panel.component.anc.ui.viewmodel

import android.content.Intent
import androidx.slice.Slice
import androidx.slice.SliceItem
import com.android.systemui.volume.panel.component.anc.domain.AncAvailabilityCriteria
import com.android.systemui.volume.panel.component.anc.domain.interactor.AncSliceInteractor
import com.android.systemui.volume.panel.component.anc.domain.model.AncSlices
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Volume Panel ANC component view model. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class AncViewModel
@Inject
constructor(
    @VolumePanelScope private val coroutineScope: CoroutineScope,
    private val interactor: AncSliceInteractor,
    private val availabilityCriteria: AncAvailabilityCriteria,
) {

    val isAvailable: Flow<Boolean>
        get() = availabilityCriteria.isAvailable()

    /** ANC [Slice]. Null when there is no slice available for ANC. */
    val popupSlice: StateFlow<Slice?> =
        interactor.ancSlices
            .filterIsInstance<AncSlices.Ready>()
            .map { it.popupSlice }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)

    /** Button [Slice] to be shown in the VolumePanel. Null when there is no ANC Slice available. */
    val buttonSlice: StateFlow<Slice?> =
        interactor.ancSlices
            .filterIsInstance<AncSlices.Ready>()
            .map { it.buttonSlice }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)

    fun isClickable(slice: Slice?): Boolean {
        slice ?: return false
        val slices = ArrayDeque<SliceItem>()
        slices.addAll(slice.items)
        while (slices.isNotEmpty()) {
            val item: SliceItem = slices.removeFirst()
            when (item.format) {
                android.app.slice.SliceItem.FORMAT_ACTION -> {
                    val itemActionIntent: Intent? = item.action?.intent
                    if (itemActionIntent?.hasExtra(EXTRA_ANC_ENABLED) == true) {
                        return itemActionIntent.getBooleanExtra(EXTRA_ANC_ENABLED, true)
                    }
                }
                android.app.slice.SliceItem.FORMAT_SLICE -> {
                    item.slice?.items?.let(slices::addAll)
                }
            }
        }
        return true
    }

    private companion object {
        const val EXTRA_ANC_ENABLED = "EXTRA_ANC_ENABLED"
    }

    /** Call this to update [popupSlice] width in a reaction to container size change. */
    fun onPopupSliceWidthChanged(width: Int) {
        interactor.onPopupSliceWidthChanged(width)
    }

    /** Call this to update [buttonSlice] width in a reaction to container size change. */
    fun onButtonSliceWidthChanged(width: Int) {
        interactor.onButtonSliceWidthChanged(width)
    }
}
