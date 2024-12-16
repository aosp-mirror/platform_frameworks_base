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

package com.android.systemui.volume.dialog.sliders.ui.viewmodel

import android.media.AudioManager
import androidx.annotation.DrawableRes
import com.android.settingslib.notification.domain.interactor.NotificationsSoundPolicyInteractor
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

class VolumeDialogSliderIconProvider
@Inject
constructor(
    private val notificationsSoundPolicyInteractor: NotificationsSoundPolicyInteractor,
    private val audioVolumeInteractor: AudioVolumeInteractor,
) {

    @DrawableRes
    fun getStreamIcon(
        stream: Int,
        level: Int,
        levelMin: Int,
        levelMax: Int,
        isMuted: Boolean,
        isRoutedToBluetooth: Boolean,
    ): Flow<Int> {
        return combine(
            notificationsSoundPolicyInteractor.isZenMuted(AudioStream(stream)),
            ringerModeForStream(stream),
        ) { isZenMuted, ringerMode ->
            val isStreamOffline = level == 0 || isMuted
            if (isZenMuted) {
                // TODO(b/372466264) use icon for the corresponding zenmode
                return@combine com.android.internal.R.drawable.ic_qs_dnd
            }
            when (ringerMode?.value) {
                AudioManager.RINGER_MODE_VIBRATE ->
                    return@combine R.drawable.ic_volume_ringer_vibrate
                AudioManager.RINGER_MODE_SILENT -> return@combine R.drawable.ic_ring_volume_off
            }
            if (isRoutedToBluetooth) {
                return@combine if (stream == AudioManager.STREAM_VOICE_CALL) {
                    R.drawable.ic_volume_bt_sco
                } else {
                    if (isStreamOffline) {
                        R.drawable.ic_volume_media_bt_mute
                    } else {
                        R.drawable.ic_volume_media_bt
                    }
                }
            }

            return@combine if (isStreamOffline) {
                getMutedIconForStream(stream) ?: getIconForStream(stream)
            } else {
                if (level < (levelMax + levelMin) / 2) {
                    // This icon is different on TV
                    R.drawable.ic_volume_media_low
                } else {
                    getIconForStream(stream)
                }
            }
        }
    }

    @DrawableRes
    private fun getMutedIconForStream(stream: Int): Int? {
        return when (stream) {
            AudioManager.STREAM_MUSIC -> R.drawable.ic_volume_media_mute
            AudioManager.STREAM_NOTIFICATION -> R.drawable.ic_volume_ringer_mute
            AudioManager.STREAM_ALARM -> R.drawable.ic_volume_alarm_mute
            AudioManager.STREAM_SYSTEM -> R.drawable.ic_volume_system_mute
            else -> null
        }
    }

    @DrawableRes
    private fun getIconForStream(stream: Int): Int {
        return when (stream) {
            AudioManager.STREAM_ACCESSIBILITY -> R.drawable.ic_volume_accessibility
            AudioManager.STREAM_MUSIC -> R.drawable.ic_volume_media
            AudioManager.STREAM_RING -> R.drawable.ic_ring_volume
            AudioManager.STREAM_NOTIFICATION -> R.drawable.ic_volume_ringer
            AudioManager.STREAM_ALARM -> R.drawable.ic_alarm
            AudioManager.STREAM_VOICE_CALL -> com.android.internal.R.drawable.ic_phone
            AudioManager.STREAM_SYSTEM -> R.drawable.ic_volume_system
            else -> error("Unsupported stream: $stream")
        }
    }

    /**
     * Emits [RingerMode] for the [stream] if it's affecting it and null when [RingerMode] doesn't
     * affect the [stream]
     */
    private fun ringerModeForStream(stream: Int): Flow<RingerMode?> {
        return if (stream == AudioManager.STREAM_RING) {
            audioVolumeInteractor.ringerMode
        } else {
            flowOf(null)
        }
    }
}
