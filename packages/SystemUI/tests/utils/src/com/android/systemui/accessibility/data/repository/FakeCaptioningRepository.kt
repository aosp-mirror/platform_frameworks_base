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

package com.android.systemui.accessibility.data.repository

import com.android.systemui.accessibility.data.model.CaptioningModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeCaptioningRepository : CaptioningRepository {

    private val mutableCaptioningModel = MutableStateFlow<CaptioningModel?>(null)
    override val captioningModel: StateFlow<CaptioningModel?> = mutableCaptioningModel.asStateFlow()

    override suspend fun setIsSystemAudioCaptioningEnabled(isEnabled: Boolean) {
        mutableCaptioningModel.value =
            CaptioningModel(
                isSystemAudioCaptioningEnabled = isEnabled,
                isSystemAudioCaptioningUiEnabled =
                    mutableCaptioningModel.value?.isSystemAudioCaptioningUiEnabled == true,
            )
    }

    fun setIsSystemAudioCaptioningUiEnabled(isEnabled: Boolean) {
        mutableCaptioningModel.value =
            CaptioningModel(
                isSystemAudioCaptioningEnabled =
                    mutableCaptioningModel.value?.isSystemAudioCaptioningEnabled == true,
                isSystemAudioCaptioningUiEnabled = isEnabled,
            )
    }
}
