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
 * limitations under the License
 */

package com.android.systemui.common.ui.data.repository

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeConfigurationRepository : ConfigurationRepository {
    private val onAnyConfigurationChangeChannel = Channel<Unit>()
    override val onAnyConfigurationChange: Flow<Unit> =
        onAnyConfigurationChangeChannel.receiveAsFlow()

    private val _scaleForResolution = MutableStateFlow(1f)
    override val scaleForResolution: Flow<Float> = _scaleForResolution.asStateFlow()

    suspend fun onAnyConfigurationChange() {
        onAnyConfigurationChangeChannel.send(Unit)
    }

    fun setScaleForResolution(scale: Float) {
        _scaleForResolution.value = scale
    }

    override fun getResolutionScale(): Float {
        return _scaleForResolution.value
    }
}
