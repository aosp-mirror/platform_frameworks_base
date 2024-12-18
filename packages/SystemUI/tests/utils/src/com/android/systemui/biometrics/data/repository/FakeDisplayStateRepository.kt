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
 *
 */

package com.android.systemui.biometrics.data.repository

import android.util.Size
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class FakeDisplayStateRepository @Inject constructor() : DisplayStateRepository {
    private val _isInRearDisplayMode = MutableStateFlow<Boolean>(false)
    override val isInRearDisplayMode: StateFlow<Boolean> = _isInRearDisplayMode.asStateFlow()

    private val _currentRotation = MutableStateFlow<DisplayRotation>(DisplayRotation.ROTATION_0)
    override val currentRotation: StateFlow<DisplayRotation> = _currentRotation.asStateFlow()

    private val _currentDisplaySize = MutableStateFlow<Size>(Size(0, 0))
    override val currentDisplaySize: StateFlow<Size> = _currentDisplaySize.asStateFlow()

    private val _isLargeScreen = MutableStateFlow<Boolean>(false)
    override val isLargeScreen: StateFlow<Boolean> = _isLargeScreen.asStateFlow()

    override val isReverseDefaultRotation = false

    fun setIsInRearDisplayMode(isInRearDisplayMode: Boolean) {
        _isInRearDisplayMode.value = isInRearDisplayMode
    }

    fun setCurrentRotation(currentRotation: DisplayRotation) {
        _currentRotation.value = currentRotation
    }

    fun setCurrentDisplaySize(size: Size) {
        _currentDisplaySize.value = size
    }

    fun setIsLargeScreen(isLargeScreen: Boolean) {
        _isLargeScreen.value = isLargeScreen
    }
}

@Module
interface FakeDisplayStateRepositoryModule {
    @Binds fun bindFake(fake: FakeDisplayStateRepository): DisplayStateRepository
}
