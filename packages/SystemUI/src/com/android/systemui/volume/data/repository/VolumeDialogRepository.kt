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

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A repository that encapsulates the states for Volume Dialog. */
@SysUISingleton
class VolumeDialogRepository @Inject constructor() {
    private val _isDialogVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)
    /** Whether the Volume Dialog is visible. */
    val isDialogVisible = _isDialogVisible.asStateFlow()

    /** Sets whether the Volume Dialog is visible. */
    fun setDialogVisibility(isVisible: Boolean) {
        _isDialogVisible.value = isVisible
    }
}
