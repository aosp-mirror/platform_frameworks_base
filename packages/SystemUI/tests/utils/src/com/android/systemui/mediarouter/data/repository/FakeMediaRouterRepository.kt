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

package com.android.systemui.mediarouter.data.repository

import android.media.projection.StopReason
import com.android.systemui.statusbar.policy.CastDevice
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMediaRouterRepository : MediaRouterRepository {
    override val castDevices: MutableStateFlow<List<CastDevice>> = MutableStateFlow(emptyList())

    var lastStoppedDevice: CastDevice? = null
        private set

    override fun stopCasting(device: CastDevice, @StopReason stopReason: Int) {
        lastStoppedDevice = device
    }
}
