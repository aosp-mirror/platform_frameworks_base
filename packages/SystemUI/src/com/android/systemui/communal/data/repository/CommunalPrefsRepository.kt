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

package com.android.systemui.communal.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.UserInfo
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserFileManagerExt.observeSharedPreferences
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Stores simple preferences for the current user in communal hub. For use cases like "has the CTA
 * tile been dismissed?"
 */
interface CommunalPrefsRepository {

    /** Whether the CTA tile has been dismissed. */
    val isCtaDismissed: Flow<Boolean>

    /** Save the CTA tile dismissed state for the current user. */
    suspend fun setCtaDismissedForCurrentUser()
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalPrefsRepositoryImpl
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val userRepository: UserRepository,
    private val userFileManager: UserFileManager,
) : CommunalPrefsRepository {

    override val isCtaDismissed: Flow<Boolean> =
        userRepository.selectedUserInfo
            .flatMapLatest(::observeCtaDismissState)
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    override suspend fun setCtaDismissedForCurrentUser() =
        withContext(bgDispatcher) {
            getSharedPrefsForUser(userRepository.getSelectedUserInfo())
                .edit()
                .putBoolean(CTA_DISMISSED_STATE, true)
                .apply()
        }

    private fun observeCtaDismissState(user: UserInfo): Flow<Boolean> =
        userFileManager
            .observeSharedPreferences(FILE_NAME, Context.MODE_PRIVATE, user.id)
            // Emit at the start of collection to ensure we get an initial value
            .onStart { emit(Unit) }
            .map { getCtaDismissedState() }
            .flowOn(bgDispatcher)

    private suspend fun getCtaDismissedState(): Boolean =
        withContext(bgDispatcher) {
            getSharedPrefsForUser(userRepository.getSelectedUserInfo())
                .getBoolean(CTA_DISMISSED_STATE, false)
        }

    private fun getSharedPrefsForUser(user: UserInfo): SharedPreferences {
        return userFileManager.getSharedPreferences(
            FILE_NAME,
            Context.MODE_PRIVATE,
            user.id,
        )
    }

    companion object {
        const val TAG = "CommunalRepository"
        const val FILE_NAME = "communal_hub_prefs"
        const val CTA_DISMISSED_STATE = "cta_dismissed"
    }
}
