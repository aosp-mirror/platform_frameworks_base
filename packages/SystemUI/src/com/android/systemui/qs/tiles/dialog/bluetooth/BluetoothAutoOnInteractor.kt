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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Interactor class responsible for interacting with the Bluetooth Auto-On feature. */
@SysUISingleton
class BluetoothAutoOnInteractor
@Inject
constructor(
    private val bluetoothAutoOnRepository: BluetoothAutoOnRepository,
) {

    val isEnabled = bluetoothAutoOnRepository.getValue.map { it == ENABLED }.distinctUntilChanged()

    /**
     * Checks if the auto on value is present in the repository.
     *
     * @return `true` if a value is present (i.e, the feature is enabled by the Bluetooth server).
     */
    suspend fun isValuePresent(): Boolean = bluetoothAutoOnRepository.isValuePresent()

    /**
     * Sets enabled or disabled based on the provided value.
     *
     * @param value `true` to enable the feature, `false` to disable it.
     */
    suspend fun setEnabled(value: Boolean) {
        if (!isValuePresent()) {
            Log.e(TAG, "Trying to set toggle value while feature not available.")
        } else {
            val newValue = if (value) ENABLED else DISABLED
            bluetoothAutoOnRepository.setValue(newValue)
        }
    }

    companion object {
        private const val TAG = "BluetoothAutoOnInteractor"
        const val DISABLED = 0
        const val ENABLED = 1
    }
}
