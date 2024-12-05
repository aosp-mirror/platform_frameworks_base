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

package com.android.systemui.settings

import android.content.ContentResolver
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepositoryImpl
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepositoryImpl
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.settings.repository.UserAwareSecureSettingsRepository
import com.android.systemui.util.settings.repository.UserAwareSystemSettingsRepository
import dagger.Lazy
import dagger.Module
import dagger.Provides
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

@Module
object UserSettingsRepositoryModule {
    @JvmStatic
    @Provides
    @SysUISingleton
    fun provideSecureSettingsRepository(
        secureSettings: Lazy<SecureSettings>,
        userRepository: Lazy<UserRepository>,
        contentResolver: Lazy<ContentResolver>,
        @Background backgroundDispatcher: CoroutineDispatcher,
        @Background backgroundContext: CoroutineContext,
    ): SecureSettingsRepository {
        return if (Flags.userAwareSettingsRepositories()) {
            UserAwareSecureSettingsRepository(
                secureSettings.get(),
                userRepository.get(),
                backgroundDispatcher,
                backgroundContext,
            )
        } else {
            SecureSettingsRepositoryImpl(contentResolver.get(), backgroundDispatcher)
        }
    }

    @JvmStatic
    @Provides
    @SysUISingleton
    fun provideSystemSettingsRepository(
        systemSettings: Lazy<SystemSettings>,
        userRepository: Lazy<UserRepository>,
        contentResolver: Lazy<ContentResolver>,
        @Background backgroundDispatcher: CoroutineDispatcher,
        @Background backgroundContext: CoroutineContext,
    ): SystemSettingsRepository {
        return if (Flags.userAwareSettingsRepositories()) {
            UserAwareSystemSettingsRepository(
                systemSettings.get(),
                userRepository.get(),
                backgroundDispatcher,
                backgroundContext,
            )
        } else {
            SystemSettingsRepositoryImpl(contentResolver.get(), backgroundDispatcher)
        }
    }
}
