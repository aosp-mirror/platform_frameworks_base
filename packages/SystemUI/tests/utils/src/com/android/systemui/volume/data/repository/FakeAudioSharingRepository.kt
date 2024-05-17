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

package com.android.systemui.volume.data.repository

import com.android.settingslib.volume.data.repository.AudioSharingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAudioSharingRepository : AudioSharingRepository {
    private val mutableInAudioSharing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val inAudioSharing: Flow<Boolean> = mutableInAudioSharing

    fun setInAudioSharing(state: Boolean) {
        mutableInAudioSharing.value = state
    }
}
