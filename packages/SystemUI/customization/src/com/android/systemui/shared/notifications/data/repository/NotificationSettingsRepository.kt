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
import com.android.systemui.shared.notifications.shared.model.NotificationSettingsModel
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

/** Provides access to state related to notifications. */
class NotificationSettingsRepository(
    scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val secureSettingsRepository: SecureSettingsRepository,
) {
    /** The current state of the notification setting. */
    val settings: SharedFlow<NotificationSettingsModel> =
        secureSettingsRepository
            .intSetting(
                name = Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
            )
            .map { lockScreenShowNotificationsInt ->
                NotificationSettingsModel(
                    isShowNotificationsOnLockScreenEnabled = lockScreenShowNotificationsInt == 1,
                )
            }
            .shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                replay = 1,
            )

    suspend fun getSettings(): NotificationSettingsModel {
        return withContext(backgroundDispatcher) {
            NotificationSettingsModel(
                isShowNotificationsOnLockScreenEnabled =
                    secureSettingsRepository.get(
                        name = Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                        defaultValue = 0,
                    ) == 1
            )
        }
    }

    suspend fun setSettings(model: NotificationSettingsModel) {
        withContext(backgroundDispatcher) {
            secureSettingsRepository.set(
                name = Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                value = if (model.isShowNotificationsOnLockScreenEnabled) 1 else 0,
            )
        }
    }
}
