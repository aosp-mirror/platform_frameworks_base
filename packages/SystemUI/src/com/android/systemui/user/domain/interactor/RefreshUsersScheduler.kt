/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.user.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Encapsulates logic for pausing, unpausing, and scheduling a delayed job. */
@SysUISingleton
class RefreshUsersScheduler
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val repository: UserRepository,
) {
    private var scheduledUnpauseJob: Job? = null
    private var isPaused = false

    fun pause() {
        applicationScope.launch(mainDispatcher) {
            isPaused = true
            scheduledUnpauseJob?.cancel()
            scheduledUnpauseJob =
                applicationScope.launch {
                    delay(PAUSE_REFRESH_USERS_TIMEOUT_MS)
                    unpauseAndRefresh()
                }
        }
    }

    fun unpauseAndRefresh() {
        applicationScope.launch(mainDispatcher) {
            isPaused = false
            refreshIfNotPaused()
        }
    }

    fun refreshIfNotPaused() {
        applicationScope.launch(mainDispatcher) {
            if (isPaused) {
                return@launch
            }

            repository.refreshUsers()
        }
    }

    companion object {
        private const val PAUSE_REFRESH_USERS_TIMEOUT_MS = 3000L
    }
}
