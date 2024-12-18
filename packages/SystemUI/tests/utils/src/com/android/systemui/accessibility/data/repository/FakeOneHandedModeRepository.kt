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

package com.android.systemui.accessibility.data.repository

import android.os.UserHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeOneHandedModeRepository : OneHandedModeRepository {
    private val userMap = mutableMapOf<Int, MutableStateFlow<Boolean>>()

    override fun isEnabled(userHandle: UserHandle): StateFlow<Boolean> {
        return getFlow(userHandle.identifier)
    }

    override suspend fun setIsEnabled(isEnabled: Boolean, userHandle: UserHandle): Boolean {
        getFlow(userHandle.identifier).value = isEnabled
        return true
    }

    /** initializes the flow if already not */
    private fun getFlow(userId: Int): MutableStateFlow<Boolean> {
        return userMap.getOrPut(userId) { MutableStateFlow(false) }
    }
}
