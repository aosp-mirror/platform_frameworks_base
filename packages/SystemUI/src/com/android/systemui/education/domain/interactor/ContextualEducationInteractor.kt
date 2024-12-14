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

package com.android.systemui.education.domain.interactor

import com.android.systemui.CoreStartable
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.contextualeducation.GestureType.HOME
import com.android.systemui.contextualeducation.GestureType.OVERVIEW
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.dagger.ContextualEducationModule.EduClock
import com.android.systemui.education.data.model.EduDeviceConnectionTime
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.education.data.repository.ContextualEducationRepository
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Allows updating education data (e.g. signal count, shortcut time) for different gesture types.
 * Change user education repository when user is changed.
 */
@SysUISingleton
class ContextualEducationInteractor
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @EduClock private val clock: Clock,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val repository: ContextualEducationRepository,
) : CoreStartable {

    val backGestureModelFlow = readEduModelsOnSignalCountChanged(BACK)
    val homeGestureModelFlow = readEduModelsOnSignalCountChanged(HOME)
    val overviewGestureModelFlow = readEduModelsOnSignalCountChanged(OVERVIEW)
    val allAppsGestureModelFlow = readEduModelsOnSignalCountChanged(ALL_APPS)
    val eduDeviceConnectionTimeFlow =
        repository.readEduDeviceConnectionTime().distinctUntilChanged()

    val keyboardShortcutTriggered = repository.keyboardShortcutTriggered

    override fun start() {
        backgroundScope.launch {
            selectedUserInteractor.selectedUser.collectLatest { repository.setUser(it) }
        }
    }

    private fun readEduModelsOnSignalCountChanged(gestureType: GestureType): Flow<GestureEduModel> {
        return repository
            .readGestureEduModelFlow(gestureType)
            .distinctUntilChanged(
                areEquivalent = { old, new -> old.signalCount == new.signalCount }
            )
            .flowOn(backgroundDispatcher)
    }

    suspend fun getEduDeviceConnectionTime(): EduDeviceConnectionTime {
        return repository.readEduDeviceConnectionTime().first()
    }

    suspend fun incrementSignalCount(gestureType: GestureType) {
        repository.updateGestureEduModel(gestureType) {
            it.copy(
                signalCount = it.signalCount + 1,
                usageSessionStartTime =
                    if (it.signalCount == 0) clock.instant() else it.usageSessionStartTime
            )
        }
    }

    suspend fun updateShortcutTriggerTime(gestureType: GestureType) {
        repository.updateGestureEduModel(gestureType) {
            it.copy(lastShortcutTriggeredTime = clock.instant())
        }
    }

    suspend fun updateOnEduTriggered(gestureType: GestureType) {
        repository.updateGestureEduModel(gestureType) {
            it.copy(
                // Reset signal counter and usageSessionStartTime after edu triggered
                signalCount = 0,
                lastEducationTime = clock.instant(),
                educationShownCount = it.educationShownCount + 1,
                usageSessionStartTime = null
            )
        }
    }

    suspend fun startNewUsageSession(gestureType: GestureType) {
        repository.updateGestureEduModel(gestureType) {
            it.copy(usageSessionStartTime = clock.instant(), signalCount = 1)
        }
    }

    suspend fun updateKeyboardFirstConnectionTime() {
        repository.updateEduDeviceConnectionTime {
            it.copy(keyboardFirstConnectionTime = clock.instant())
        }
    }

    suspend fun updateTouchpadFirstConnectionTime() {
        repository.updateEduDeviceConnectionTime {
            it.copy(touchpadFirstConnectionTime = clock.instant())
        }
    }
}
