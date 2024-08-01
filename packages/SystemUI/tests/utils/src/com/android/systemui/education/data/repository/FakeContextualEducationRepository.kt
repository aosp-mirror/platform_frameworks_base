/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.data.repository

import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.education.data.model.GestureEduModel
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeContextualEducationRepository(private val clock: Clock) : ContextualEducationRepository {

    private val userGestureMap = mutableMapOf<Int, GestureEduModel>()
    private val _gestureEduModels = MutableStateFlow(GestureEduModel())
    private val gestureEduModelsFlow = _gestureEduModels.asStateFlow()
    private var currentUser: Int = 0

    override fun setUser(userId: Int) {
        if (!userGestureMap.contains(userId)) {
            userGestureMap[userId] = GestureEduModel()
        }
        // save data of current user to the map
        userGestureMap[currentUser] = _gestureEduModels.value
        // switch to data of new user
        _gestureEduModels.value = userGestureMap[userId]!!
    }

    override fun readGestureEduModelFlow(gestureType: GestureType): Flow<GestureEduModel> {
        return gestureEduModelsFlow
    }

    override suspend fun incrementSignalCount(gestureType: GestureType) {
        val originalModel = _gestureEduModels.value
        _gestureEduModels.value =
            originalModel.copy(
                signalCount = _gestureEduModels.value.signalCount + 1,
            )
    }

    override suspend fun updateShortcutTriggerTime(gestureType: GestureType) {
        val originalModel = _gestureEduModels.value
        _gestureEduModels.value = originalModel.copy(lastShortcutTriggeredTime = clock.instant())
    }
}
