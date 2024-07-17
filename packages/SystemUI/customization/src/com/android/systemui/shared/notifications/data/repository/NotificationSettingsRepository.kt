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

package com.android.systemui.shared.notifications.data.repository

import android.provider.Settings
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Provides access to state related to notification settings. */
class NotificationSettingsRepository(
    private val scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val secureSettingsRepository: SecureSettingsRepository,
) {
    val isNotificationHistoryEnabled: Flow<Boolean> =
        secureSettingsRepository
            .intSetting(name = Settings.Secure.NOTIFICATION_HISTORY_ENABLED)
            .map { it == 1 }
            .distinctUntilChanged()

    /** The current state of the notification setting. */
    suspend fun isShowNotificationsOnLockScreenEnabled(): StateFlow<Boolean> =
        secureSettingsRepository
            .intSetting(
                name = Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
            )
            .map { it == 1 }
            .flowOn(backgroundDispatcher)
            .stateIn(
                scope = scope,
            )

    suspend fun setShowNotificationsOnLockscreenEnabled(enabled: Boolean) {
        withContext(backgroundDispatcher) {
            secureSettingsRepository.setInt(
                name = Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                value = if (enabled) 1 else 0,
            )
        }
    }
}
