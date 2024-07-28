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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.education.data.repository.ContextualEducationRepository
import com.android.systemui.shared.education.GestureType
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val selectedUserInteractor: SelectedUserInteractor,
    private val repository: ContextualEducationRepository,
) : CoreStartable {

    val backGestureModelFlow = readEduModelsOnSignalCountChanged(GestureType.BACK_GESTURE)

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

    suspend fun incrementSignalCount(gestureType: GestureType) =
        repository.incrementSignalCount(gestureType)

    suspend fun updateShortcutTriggerTime(gestureType: GestureType) =
        repository.updateShortcutTriggerTime(gestureType)
}
