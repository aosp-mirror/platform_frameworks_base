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

import android.content.res.Configuration
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class FakeConfigurationRepository @Inject constructor() : ConfigurationRepository {
    private val _onAnyConfigurationChange =
        MutableSharedFlow<Unit>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val onAnyConfigurationChange: Flow<Unit> = _onAnyConfigurationChange.asSharedFlow()

    private val _onConfigurationChange =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val onConfigurationChange: Flow<Unit> = _onConfigurationChange.asSharedFlow()

    private val _configurationChangeValues =
        MutableSharedFlow<Configuration>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val configurationValues: Flow<Configuration> =
        _configurationChangeValues.asSharedFlow()

    private val _scaleForResolution = MutableStateFlow(1f)
    override val scaleForResolution: Flow<Float> = _scaleForResolution.asStateFlow()

    private val pixelSizes = mutableMapOf<Int, MutableStateFlow<Int>>()

    fun onAnyConfigurationChange() {
        _onAnyConfigurationChange.tryEmit(Unit)
    }

    fun onConfigurationChange() {
        _onConfigurationChange.tryEmit(Unit)
    }

    fun onConfigurationChange(configChange: Configuration) {
        _configurationChangeValues.tryEmit(configChange)
        onAnyConfigurationChange()
    }

    fun setScaleForResolution(scale: Float) {
        _scaleForResolution.value = scale
    }

    override fun getResolutionScale(): Float = _scaleForResolution.value

    override fun getDimensionPixelSize(id: Int): Int = pixelSizes[id]?.value ?: 0

    fun setDimensionPixelSize(id: Int, pixelSize: Int) {
        pixelSizes.getOrPut(id) { MutableStateFlow(pixelSize) }.value = pixelSize
    }
}

@Module
interface FakeConfigurationRepositoryModule {
    @Binds fun bindFake(fake: FakeConfigurationRepository): ConfigurationRepository
}
