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

import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxy
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/**
 * Repository for observing values of [Settings.Secure] for the currently active user. That means
 * when user is switched and the new user has different value, flow will emit new value.
 */
interface UserAwareSecureSettingsRepository {

    /**
     * Emits boolean value of the setting for active user. Also emits starting value when
     * subscribed.
     * See: [SettingsProxy.getBool].
     */
    fun boolSettingForActiveUser(name: String, defaultValue: Boolean = false): Flow<Boolean>
}

@SysUISingleton
@OptIn(ExperimentalCoroutinesApi::class)
class UserAwareSecureSettingsRepositoryImpl @Inject constructor(
    private val secureSettings: SecureSettings,
    private val userRepository: UserRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : UserAwareSecureSettingsRepository {

    override fun boolSettingForActiveUser(name: String, defaultValue: Boolean): Flow<Boolean> =
        userRepository.selectedUserInfo
            .flatMapLatest { userInfo -> settingObserver(name, defaultValue, userInfo.id) }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    private fun settingObserver(name: String, defaultValue: Boolean, userId: Int): Flow<Boolean> {
        return secureSettings
            .observerFlow(userId, name)
            .onStart { emit(Unit) }
            .map { secureSettings.getBoolForUser(name, defaultValue, userId) }
    }
}