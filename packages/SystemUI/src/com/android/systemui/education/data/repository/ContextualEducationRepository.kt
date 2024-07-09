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
import com.android.systemui.shared.education.GestureType
import javax.inject.Inject

/**
 * Provide methods to read and update on field level and allow setting datastore when user is
 * changed
 */
@SysUISingleton
class ContextualEducationRepository
@Inject
constructor(private val userEduRepository: UserContextualEducationRepository) {
    /** To change data store when user is changed */
    fun setUser(userId: Int) = userEduRepository.setUser(userId)

    fun readGestureEduModelFlow(gestureType: GestureType) =
        userEduRepository.readGestureEduModelFlow(gestureType)

    suspend fun incrementSignalCount(gestureType: GestureType) {
        userEduRepository.updateGestureEduModel(gestureType) {
            it.copy(signalCount = it.signalCount + 1)
        }
    }
}
