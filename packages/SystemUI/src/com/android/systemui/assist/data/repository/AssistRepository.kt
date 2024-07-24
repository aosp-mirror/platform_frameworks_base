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

package com.android.systemui.assist.data.repository

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@SysUISingleton
class AssistRepository @Inject constructor() {
    private val _latestInvocationType =
        MutableSharedFlow<Int>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The type of the latest invocation of the assistant. */
    val latestInvocationType: SharedFlow<Int> = _latestInvocationType.asSharedFlow()

    /** Sets the type of the latest invocation of the assistant. */
    fun setLatestInvocationType(type: Int) {
        _latestInvocationType.tryEmit(type)
    }
}
