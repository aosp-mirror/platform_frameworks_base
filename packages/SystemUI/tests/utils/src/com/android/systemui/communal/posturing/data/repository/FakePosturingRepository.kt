/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.posturing.data.repository

import com.android.systemui.communal.posturing.shared.model.PosturedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakePosturingRepository : PosturingRepository {
    private val _postured = MutableStateFlow<PosturedState>(PosturedState.Unknown)

    override val posturedState: StateFlow<PosturedState> = _postured.asStateFlow()

    fun setPosturedState(state: PosturedState) {
        _postured.value = state
    }
}

val PosturingRepository.fake
    get() = this as FakePosturingRepository
