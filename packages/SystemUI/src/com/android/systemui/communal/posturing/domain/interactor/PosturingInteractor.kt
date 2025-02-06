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

package com.android.systemui.communal.posturing.domain.interactor

import com.android.systemui.communal.posturing.data.repository.PosturingRepository
import com.android.systemui.communal.posturing.shared.model.PosturedState
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

@SysUISingleton
class PosturingInteractor @Inject constructor(repository: PosturingRepository) {
    private val debugPostured = MutableStateFlow<PosturedState>(PosturedState.Unknown)

    val postured: Flow<Boolean> =
        combine(repository.posturedState, debugPostured) { postured, debugValue ->
            debugValue.asBoolean() ?: postured.asBoolean() ?: false
        }

    fun setValueForDebug(value: PosturedState) {
        debugPostured.value = value
    }
}

fun PosturedState.asBoolean(): Boolean? {
    return when (this) {
        is PosturedState.Postured -> true
        PosturedState.NotPostured -> false
        PosturedState.Unknown -> null
    }
}
