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

package com.android.systemui.bluetooth.qsdialog

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Interactor class responsible for interacting with the Bluetooth Auto-On feature. */
@SysUISingleton
class BluetoothAutoOnInteractor
@Inject
constructor(
    private val bluetoothAutoOnRepository: BluetoothAutoOnRepository,
) {

    val isEnabled = bluetoothAutoOnRepository.isAutoOn

    /** Checks if the auto on feature is supported. */
    suspend fun isAutoOnSupported(): Boolean = bluetoothAutoOnRepository.isAutoOnSupported()

    /**
     * Sets enabled or disabled based on the provided value.
     *
     * @param value `true` to enable the feature, `false` to disable it.
     */
    suspend fun setEnabled(value: Boolean) {
        if (!isAutoOnSupported()) {
            Log.e(TAG, "Trying to set toggle value while feature not available.")
        } else {
            bluetoothAutoOnRepository.setAutoOn(value)
        }
    }

    companion object {
        private const val TAG = "BluetoothAutoOnInteractor"
    }
}
