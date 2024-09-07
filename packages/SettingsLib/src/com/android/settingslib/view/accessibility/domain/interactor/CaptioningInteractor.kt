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

package com.android.settingslib.view.accessibility.domain.interactor

import com.android.settingslib.view.accessibility.data.repository.CaptioningRepository
import kotlinx.coroutines.flow.StateFlow

class CaptioningInteractor(private val repository: CaptioningRepository) {

    val isSystemAudioCaptioningEnabled: StateFlow<Boolean>
        get() = repository.isSystemAudioCaptioningEnabled

    val isSystemAudioCaptioningUiEnabled: StateFlow<Boolean>
        get() = repository.isSystemAudioCaptioningUiEnabled

    suspend fun setIsSystemAudioCaptioningEnabled(enabled: Boolean) =
        repository.setIsSystemAudioCaptioningEnabled(enabled)
}
