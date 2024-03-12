/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.statusbar.notification.domain.interactor

import android.app.NotificationManager
import android.media.AudioManager
import android.provider.Settings
import android.service.notification.ZenModeConfig
import com.android.settingslib.statusbar.notification.data.model.ZenMode
import com.android.settingslib.statusbar.notification.data.repository.NotificationsSoundPolicyRepository
import com.android.settingslib.volume.shared.model.AudioStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Determines notification sounds state and limitations. */
class NotificationsSoundPolicyInteractor(
    private val repository: NotificationsSoundPolicyRepository
) {

    /** @see NotificationManager.getNotificationPolicy */
    val notificationPolicy: StateFlow<NotificationManager.Policy?>
        get() = repository.notificationPolicy

    /** @see NotificationManager.getZenMode */
    val zenMode: StateFlow<ZenMode?>
        get() = repository.zenMode

    /** Checks if [notificationPolicy] allows alarms. */
    val areAlarmsAllowed: Flow<Boolean?> = notificationPolicy.map { it?.allowAlarms() }

    /** Checks if [notificationPolicy] allows media. */
    val isMediaAllowed: Flow<Boolean?> = notificationPolicy.map { it?.allowMedia() }

    /** Checks if [notificationPolicy] allows system sounds. */
    val isSystemAllowed: Flow<Boolean?> = notificationPolicy.map { it?.allowSystem() }

    /** Checks if [notificationPolicy] allows ringer. */
    val isRingerAllowed: Flow<Boolean?> =
        notificationPolicy.map { policy ->
            policy ?: return@map null
            !ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(policy)
        }

    /** Checks if the [stream] is muted by either [zenMode] or [notificationPolicy]. */
    fun isZenMuted(stream: AudioStream): Flow<Boolean> {
        return combine(
            zenMode.filterNotNull(),
            areAlarmsAllowed.filterNotNull(),
            isMediaAllowed.filterNotNull(),
            isRingerAllowed.filterNotNull(),
            isSystemAllowed.filterNotNull(),
        ) { zenMode, areAlarmsAllowed, isMediaAllowed, isRingerAllowed, isSystemAllowed ->
            when (zenMode.zenMode) {
                // Everything is muted
                Settings.Global.ZEN_MODE_NO_INTERRUPTIONS -> return@combine true
                Settings.Global.ZEN_MODE_ALARMS ->
                    return@combine stream.value == AudioManager.STREAM_RING ||
                        stream.value == AudioManager.STREAM_NOTIFICATION ||
                        stream.value == AudioManager.STREAM_SYSTEM
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS -> {
                    when {
                        stream.value == AudioManager.STREAM_ALARM && !areAlarmsAllowed ->
                            return@combine true
                        stream.value == AudioManager.STREAM_MUSIC && !isMediaAllowed ->
                            return@combine true
                        stream.value == AudioManager.STREAM_SYSTEM && !isSystemAllowed ->
                            return@combine true
                        (stream.value == AudioManager.STREAM_RING ||
                            stream.value == AudioManager.STREAM_NOTIFICATION) && !isRingerAllowed ->
                            return@combine true
                    }
                }
            }
            return@combine false
        }
    }
}
