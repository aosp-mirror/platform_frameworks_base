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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.education.dagger.ContextualEducationModule.EduClock
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.shared.education.GestureType
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Encapsulates the functions of ContextualEducationRepository. */
interface ContextualEducationRepository {
    fun setUser(userId: Int)

    fun readGestureEduModelFlow(gestureType: GestureType): Flow<GestureEduModel>

    suspend fun incrementSignalCount(gestureType: GestureType)

    suspend fun updateShortcutTriggerTime(gestureType: GestureType)
}

/**
 * Provide methods to read and update on field level and allow setting datastore when user is
 * changed
 */
@SysUISingleton
class ContextualEducationRepositoryImpl
@Inject
constructor(
    @EduClock private val clock: Clock,
    private val userEduRepository: UserContextualEducationRepository
) : ContextualEducationRepository {
    /** To change data store when user is changed */
    override fun setUser(userId: Int) = userEduRepository.setUser(userId)

    override fun readGestureEduModelFlow(gestureType: GestureType) =
        userEduRepository.readGestureEduModelFlow(gestureType)

    override suspend fun incrementSignalCount(gestureType: GestureType) {
        userEduRepository.updateGestureEduModel(gestureType) {
            it.copy(signalCount = it.signalCount + 1)
        }
    }

    override suspend fun updateShortcutTriggerTime(gestureType: GestureType) {
        userEduRepository.updateGestureEduModel(gestureType) {
            it.copy(lastShortcutTriggeredTime = clock.instant())
        }
    }
}
