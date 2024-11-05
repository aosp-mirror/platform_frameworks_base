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
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Repository for observing values of [Settings.Secure] for the currently active user. That means
 * when user is switched and the new user has different value, flow will emit new value.
 */
@SysUISingleton
class UserAwareSystemSettingsRepository
@Inject
constructor(
    systemSettings: SystemSettings,
    userRepository: UserRepository,
    @Background backgroundDispatcher: CoroutineDispatcher,
    @Background bgContext: CoroutineContext,
) :
    UserAwareSettingsRepository(systemSettings, userRepository, backgroundDispatcher, bgContext),
    SystemSettingsRepository
