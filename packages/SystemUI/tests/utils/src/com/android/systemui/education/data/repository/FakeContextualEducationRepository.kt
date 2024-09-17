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
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.contextualeducation.GestureType.HOME
import com.android.systemui.contextualeducation.GestureType.OVERVIEW
import com.android.systemui.education.data.model.EduDeviceConnectionTime
import com.android.systemui.education.data.model.GestureEduModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeContextualEducationRepository : ContextualEducationRepository {

    private val userGestureMap = mutableMapOf<Int, MutableMap<GestureType, GestureEduModel>>()

    private val _backGestureEduModels = MutableStateFlow(GestureEduModel(BACK, userId = 0))
    private val backGestureEduModelsFlow = _backGestureEduModels.asStateFlow()

    private val _homeGestureEduModels = MutableStateFlow(GestureEduModel(HOME, userId = 0))
    private val homeEduModelsFlow = _homeGestureEduModels.asStateFlow()

    private val _allAppsGestureEduModels = MutableStateFlow(GestureEduModel(ALL_APPS, userId = 0))
    private val allAppsGestureEduModels = _allAppsGestureEduModels.asStateFlow()

    private val _overviewsGestureEduModels = MutableStateFlow(GestureEduModel(OVERVIEW, userId = 0))
    private val overviewsGestureEduModels = _overviewsGestureEduModels.asStateFlow()

    private val userEduDeviceConnectionTimeMap = mutableMapOf<Int, EduDeviceConnectionTime>()
    private val _eduDeviceConnectionTime = MutableStateFlow(EduDeviceConnectionTime())
    private val eduDeviceConnectionTime = _eduDeviceConnectionTime.asStateFlow()

    private val _keyboardShortcutTriggered = MutableStateFlow<GestureType?>(null)

    private var currentUser: Int = 0

    override fun setUser(userId: Int) {
        if (!userGestureMap.contains(userId)) {
            userGestureMap[userId] = createGestureEduModelMap(userId = userId)
            userEduDeviceConnectionTimeMap[userId] = EduDeviceConnectionTime()
        }
        // save data of current user to the map
        val currentUserMap = userGestureMap[currentUser]!!
        currentUserMap[BACK] = _backGestureEduModels.value
        currentUserMap[HOME] = _homeGestureEduModels.value
        currentUserMap[ALL_APPS] = _allAppsGestureEduModels.value
        currentUserMap[OVERVIEW] = _overviewsGestureEduModels.value

        // switch to data of new user
        val newUserGestureMap = userGestureMap[userId]!!
        newUserGestureMap[BACK]?.let { _backGestureEduModels.value = it }
        newUserGestureMap[HOME]?.let { _homeGestureEduModels.value = it }
        newUserGestureMap[ALL_APPS]?.let { _allAppsGestureEduModels.value = it }
        newUserGestureMap[OVERVIEW]?.let { _overviewsGestureEduModels.value = it }

        userEduDeviceConnectionTimeMap[currentUser] = _eduDeviceConnectionTime.value
        _eduDeviceConnectionTime.value = userEduDeviceConnectionTimeMap[userId]!!
    }

    private fun createGestureEduModelMap(userId: Int): MutableMap<GestureType, GestureEduModel> {
        val gestureModelMap = mutableMapOf<GestureType, GestureEduModel>()
        GestureType.values().forEach { gestureModelMap[it] = GestureEduModel(it, userId = userId) }
        return gestureModelMap
    }

    override fun readGestureEduModelFlow(gestureType: GestureType): Flow<GestureEduModel> {
        return when (gestureType) {
            BACK -> backGestureEduModelsFlow
            HOME -> homeEduModelsFlow
            ALL_APPS -> allAppsGestureEduModels
            OVERVIEW -> overviewsGestureEduModels
        }
    }

    override fun readEduDeviceConnectionTime(): Flow<EduDeviceConnectionTime> {
        return eduDeviceConnectionTime
    }

    override suspend fun updateGestureEduModel(
        gestureType: GestureType,
        transform: (GestureEduModel) -> GestureEduModel
    ) {
        val gestureModels =
            when (gestureType) {
                BACK -> _backGestureEduModels
                HOME -> _homeGestureEduModels
                ALL_APPS -> _allAppsGestureEduModels
                OVERVIEW -> _overviewsGestureEduModels
            }

        val currentModel = gestureModels.value
        gestureModels.value = transform(currentModel)
    }

    override suspend fun updateEduDeviceConnectionTime(
        transform: (EduDeviceConnectionTime) -> EduDeviceConnectionTime
    ) {
        val currentModel = _eduDeviceConnectionTime.value
        _eduDeviceConnectionTime.value = transform(currentModel)
    }

    override val keyboardShortcutTriggered: Flow<GestureType>
        get() = _keyboardShortcutTriggered.filterNotNull()

    fun setKeyboardShortcutTriggered(gestureType: GestureType) {
        _keyboardShortcutTriggered.value = gestureType
    }
}
