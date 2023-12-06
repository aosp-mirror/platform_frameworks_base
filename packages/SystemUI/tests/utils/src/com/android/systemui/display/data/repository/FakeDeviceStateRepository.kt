/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.display.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fake [DeviceStateRepository] implementation for testing. */
class FakeDeviceStateRepository : DeviceStateRepository {
    private val flow = MutableStateFlow(DeviceStateRepository.DeviceState.UNKNOWN)

    /** Emits [value] as [displays] flow value. */
    suspend fun emit(value: DeviceStateRepository.DeviceState) = flow.emit(value)

    override val state: StateFlow<DeviceStateRepository.DeviceState>
        get() = flow
}
