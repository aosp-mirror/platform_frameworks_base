/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.statusbar.phone

import android.content.Context
import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback
import com.android.internal.R

/**
 * Listens for fold state changes and reports the new folded state together with other properties
 * associated with that state.
 */
internal class FoldStateListener(
    context: Context,
    private val listener: OnFoldStateChangeListener
) : DeviceStateCallback {

    internal interface OnFoldStateChangeListener {
        /**
         * Reports that the device either became folded or unfolded.
         *
         * @param folded whether the device is folded.
         * @param willGoToSleep whether the device will go to sleep and keep the screen off.
         */
        fun onFoldStateChanged(folded: Boolean, willGoToSleep: Boolean)
    }

    private val foldedDeviceStates: IntArray =
        context.resources.getIntArray(R.array.config_foldedDeviceStates)
    private val goToSleepDeviceStates: IntArray =
        context.resources.getIntArray(R.array.config_deviceStatesOnWhichToSleep)

    private var wasFolded: Boolean? = null

    override fun onStateChanged(state: Int) {
        val isFolded = foldedDeviceStates.contains(state)
        if (wasFolded == isFolded) {
            return
        }
        wasFolded = isFolded
        val willGoToSleep = goToSleepDeviceStates.contains(state)
        listener.onFoldStateChanged(isFolded, willGoToSleep)
    }
}
