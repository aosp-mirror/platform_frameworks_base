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

package com.android.systemui.volume.panel.component.anc.data.repository

import android.bluetooth.BluetoothDevice
import android.net.Uri
import androidx.slice.Slice
import androidx.slice.SliceViewManager
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.slice.sliceForUri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/** Provides ANC slice data */
interface AncSliceRepository {

    /**
     * ANC slice with a given width. [isCollapsed] slice shows a single button, and expanded shows a
     * row buttons.
     *
     * Emits null when there is no ANC slice available. This can mean that:
     * - there is no supported device connected;
     * - there is no slice provider for the uri;
     */
    fun ancSlice(
        device: BluetoothDevice,
        width: Int,
        isCollapsed: Boolean,
        hideLabel: Boolean
    ): Flow<Slice?>
}

class AncSliceRepositoryImpl
@AssistedInject
constructor(
    @Main private val mainCoroutineContext: CoroutineContext,
    @Assisted private val sliceViewManager: SliceViewManager,
) : AncSliceRepository {

    override fun ancSlice(
        device: BluetoothDevice,
        width: Int,
        isCollapsed: Boolean,
        hideLabel: Boolean
    ): Flow<Slice?> {
        val sliceUri =
            device.getExtraControlUri(width, isCollapsed, hideLabel) ?: return flowOf(null)
        return sliceViewManager.sliceForUri(sliceUri).flowOn(mainCoroutineContext)
    }

    private fun BluetoothDevice.getExtraControlUri(
        width: Int,
        isCollapsed: Boolean,
        hideLabel: Boolean
    ): Uri? {
        val uri: String? = BluetoothUtils.getControlUriMetaData(this)
        uri ?: return null

        return if (uri.isEmpty()) {
            null
        } else {
            Uri.parse(
                "$uri$width" +
                    "&version=${SliceParameters.VERSION}" +
                    "&is_collapsed=$isCollapsed" +
                    "&hide_label=$hideLabel"
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(sliceViewManager: SliceViewManager): AncSliceRepositoryImpl
    }

    private object SliceParameters {
        /**
         * Slice version
         * 1) legacy slice
         * 2) new slice
         */
        const val VERSION = 2
    }
}
