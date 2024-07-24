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
import com.android.systemui.volume.panel.component.anc.data.repository.AncSliceRepository
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** Provides a valid slice from [AncSliceRepository]. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class AncSliceInteractor
@Inject
constructor(
    private val ancSliceRepository: AncSliceRepository,
    scope: CoroutineScope,
) {

    // Start with a positive width to check is the Slice is available.
    private val width = MutableStateFlow(1)

    /** Provides a valid ANC slice. */
    val ancSlice: SharedFlow<Slice?> =
        width
            .flatMapLatest { width -> ancSliceRepository.ancSlice(width) }
            .map { slice ->
                if (slice?.isValidSlice() == true) {
                    slice
                } else {
                    null
                }
            }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** Updates the width of the [ancSlice] */
    fun changeWidth(newWidth: Int) {
        width.value = newWidth
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
}
