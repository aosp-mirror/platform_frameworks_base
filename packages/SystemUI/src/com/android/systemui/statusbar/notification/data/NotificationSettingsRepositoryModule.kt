/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.data

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.SecureSettingsRepositoryModule
import com.android.systemui.settings.SystemSettingsRepositoryModule
import com.android.systemui.shared.notifications.data.repository.NotificationSettingsRepository
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionLogger
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Module(includes = [SecureSettingsRepositoryModule::class, SystemSettingsRepositoryModule::class])
object NotificationSettingsRepositoryModule {
    @Provides
    @SysUISingleton
    fun provideNotificationSettingsRepository(
        @Background backgroundScope: CoroutineScope,
        @Background backgroundDispatcher: CoroutineDispatcher,
        secureSettingsRepository: SecureSettingsRepository,
        systemSettingsRepository: SystemSettingsRepository,
    ): NotificationSettingsRepository =
        NotificationSettingsRepository(
            backgroundScope,
            backgroundDispatcher,
            secureSettingsRepository,
            systemSettingsRepository)

    @Provides
    @IntoMap
    @ClassKey(NotificationSettingsRepository::class)
    @SysUISingleton
    fun provideCoreStartable(
        @Application applicationScope: CoroutineScope,
        repository: NotificationSettingsRepository,
        logger: VisualInterruptionDecisionLogger
    ) = CoreStartable {
        applicationScope.launch {
            repository.isCooldownEnabled.collect { value -> logger.logCooldownSetting(value) }
        }
    }
}
