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

import android.view.View.GONE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeKeyguardSmartspaceRepository : KeyguardSmartspaceRepository {

    private val _bcSmartspaceVisibility = MutableStateFlow(GONE)
    override val bcSmartspaceVisibility: StateFlow<Int> = _bcSmartspaceVisibility
    private val _isWeatherEnabled = MutableStateFlow(true)
    override val isWeatherEnabled: StateFlow<Boolean> = _isWeatherEnabled

    override fun setBcSmartspaceVisibility(visibility: Int) {
        _bcSmartspaceVisibility.value = visibility
    }

    fun setIsWeatherEnabled(isEnabled: Boolean) {
        _isWeatherEnabled.value = isEnabled
    }
}
