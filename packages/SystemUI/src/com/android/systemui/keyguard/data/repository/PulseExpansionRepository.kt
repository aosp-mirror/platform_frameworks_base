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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.kotlin.FlowDumperImpl
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class PulseExpansionRepository @Inject constructor(dumpManager: DumpManager) :
    FlowDumperImpl(dumpManager) {
    /**
     * Whether the notification panel is expanding from the user swiping downward on a notification
     * from the pulsing state, or swiping anywhere on the screen when face bypass is enabled
     */
    val isPulseExpanding: MutableStateFlow<Boolean> =
        MutableStateFlow(false).dumpValue("pulseExpanding")
}
