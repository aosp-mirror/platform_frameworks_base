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

package com.android.systemui.globalactions.data.repository

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Encapsulates application state for global actions. */
@SysUISingleton
class GlobalActionsRepository @Inject constructor() {
    private val _isVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)
    /** Is the global actions dialog visible. */
    val isVisible = _isVisible.asStateFlow()

    /** Sets whether the global actions dialog is visible. */
    fun setVisible(isVisible: Boolean) {
        _isVisible.value = isVisible
    }
}
