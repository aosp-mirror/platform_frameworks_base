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

package com.android.systemui.util.settings.repository

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.UserSettingsProxy
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Repository for observing values of a [UserSettingsProxy], for the currently active user. That
 * means that when the user is switched and the new user has a different value, the flow will emit
 * the new value.
 */
// TODO: b/377244768 - Make internal when UserAwareSecureSettingsRepository can be made internal.
@OptIn(ExperimentalCoroutinesApi::class)
abstract class UserAwareSettingsRepository(
    private val userSettings: UserSettingsProxy,
    private val userRepository: UserRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Background private val bgContext: CoroutineContext,
) {

    fun boolSetting(name: String, defaultValue: Boolean): Flow<Boolean> =
        userRepository.selectedUserInfo
            .flatMapLatest { userInfo ->
                settingObserver(name, userInfo.id) {
                    userSettings.getBoolForUser(name, defaultValue, userInfo.id)
                }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    fun intSetting(name: String, defaultValue: Int): Flow<Int> {
        return userRepository.selectedUserInfo
            .flatMapLatest { userInfo ->
                settingObserver(name, userInfo.id) {
                    userSettings.getIntForUser(name, defaultValue, userInfo.id)
                }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
    }

    private fun <T> settingObserver(name: String, userId: Int, settingsReader: () -> T): Flow<T> {
        return userSettings
            .observerFlow(userId, name)
            .onStart { emit(Unit) }
            .map { settingsReader.invoke() }
    }

    suspend fun setInt(name: String, value: Int) {
        withContext(bgContext) {
            userSettings.putIntForUser(name, value, userRepository.getSelectedUserInfo().id)
        }
    }

    suspend fun getInt(name: String, defaultValue: Int): Int {
        return withContext(bgContext) {
            userSettings.getIntForUser(name, defaultValue, userRepository.getSelectedUserInfo().id)
        }
    }

    suspend fun setBoolean(name: String, value: Boolean) {
        withContext(bgContext) {
            userSettings.putBoolForUser(name, value, userRepository.getSelectedUserInfo().id)
        }
    }

    suspend fun getString(name: String): String? {
        return withContext(bgContext) {
            userSettings.getStringForUser(name, userRepository.getSelectedUserInfo().id)
        }
    }
}
