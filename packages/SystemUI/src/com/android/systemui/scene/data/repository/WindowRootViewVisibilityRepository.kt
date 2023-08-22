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

package com.android.systemui.scene.data.repository

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Source of truth for the visibility of various parts of the window root view. */
@SysUISingleton
class WindowRootViewVisibilityRepository @Inject constructor() {
    private val _isLockscreenOrShadeVisible = MutableStateFlow(false)
    val isLockscreenOrShadeVisible: StateFlow<Boolean> = _isLockscreenOrShadeVisible.asStateFlow()

    fun setIsLockscreenOrShadeVisible(visible: Boolean) {
        _isLockscreenOrShadeVisible.value = visible
    }
}
