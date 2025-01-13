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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.AudioManager
import androidx.annotation.DrawableRes
import com.android.settingslib.R as SettingsR
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.domain.model.ActiveZenModes
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

@SuppressLint("UseCompatLoadingForDrawables")
class VolumeDialogSliderIconProvider
@Inject
constructor(
    private val context: Context,
    private val zenModeInteractor: ZenModeInteractor,
    private val audioVolumeInteractor: AudioVolumeInteractor,
) {

    fun getAudioSharingIcon(isMuted: Boolean): Flow<Drawable> {
        return flow {
            val iconRes =
                if (isMuted) {
                    R.drawable.ic_volume_media_bt_mute
                } else {
                    R.drawable.ic_volume_media_bt
                }
            emit(context.getDrawable(iconRes)!!)
        }
    }

    fun getCastIcon(isMuted: Boolean): Flow<Drawable> {
        return flow {
            val iconRes =
                if (isMuted) {
                    SettingsR.drawable.ic_volume_remote_mute
                } else {
                    SettingsR.drawable.ic_volume_remote
                }
            emit(context.getDrawable(iconRes)!!)
        }
    }

    fun getStreamIcon(
        stream: Int,
        level: Int,
        levelMin: Int,
        levelMax: Int,
        isMuted: Boolean,
        isRoutedToBluetooth: Boolean,
    ): Flow<Drawable> {
        return combine(
            zenModeInteractor.activeModesBlockingStream(stream),
            ringerModeForStream(stream),
        ) { activeModesBlockingStream, ringerMode ->
            if (activeModesBlockingStream?.mainMode?.icon != null) {
                return@combine activeModesBlockingStream.mainMode.icon.drawable
            } else {
                context.getDrawable(
                    getIconRes(
                        stream,
                        level,
                        levelMin,
                        levelMax,
                        isMuted,
                        isRoutedToBluetooth,
                        ringerMode,
                    )
                )!!
            }
        }
    }

    @DrawableRes
    private fun getIconRes(
        stream: Int,
        level: Int,
        levelMin: Int,
        levelMax: Int,
        isMuted: Boolean,
        isRoutedToBluetooth: Boolean,
        ringerMode: RingerMode?,
    ): Int {
        val isStreamOffline = level == 0 || isMuted
        if (isRoutedToBluetooth) {
            return if (stream == AudioManager.STREAM_VOICE_CALL) {
                R.drawable.ic_volume_bt_sco
            } else {
                if (isStreamOffline) {
                    R.drawable.ic_volume_media_bt_mute
                } else {
                    R.drawable.ic_volume_media_bt
                }
            }
        }

        val isLevelLow = level < (levelMax + levelMin) / 2
        return if (isStreamOffline) {
            val ringerOfflineIcon =
                when (ringerMode?.value) {
                    AudioManager.RINGER_MODE_VIBRATE -> return R.drawable.ic_volume_ringer_vibrate
                    AudioManager.RINGER_MODE_SILENT -> return R.drawable.ic_ring_volume_off
                    else -> null
                }
            when (stream) {
                AudioManager.STREAM_MUSIC -> R.drawable.ic_volume_media_mute
                AudioManager.STREAM_NOTIFICATION ->
                    ringerOfflineIcon ?: R.drawable.ic_volume_ringer_mute
                AudioManager.STREAM_RING -> ringerOfflineIcon ?: R.drawable.ic_volume_ringer_vibrate
                AudioManager.STREAM_ALARM -> R.drawable.ic_volume_alarm_mute
                AudioManager.STREAM_SYSTEM -> R.drawable.ic_volume_system_mute
                else -> null
            }
        } else {
            null
        } ?: getIconForStream(stream = stream, isLevelLow = isLevelLow)
    }

    @DrawableRes
    private fun getIconForStream(stream: Int, isLevelLow: Boolean): Int {
        return when (stream) {
            AudioManager.STREAM_ACCESSIBILITY -> R.drawable.ic_volume_accessibility
            AudioManager.STREAM_MUSIC ->
                if (isLevelLow) {
                    // This icon is different on TV
                    R.drawable.ic_volume_media_low
                } else {
                    R.drawable.ic_volume_media
                }
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
        return if (
            stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION
        ) {
            audioVolumeInteractor.ringerMode
        } else {
            flowOf(null)
        }
    }
}

private fun ZenModeInteractor.activeModesBlockingStream(stream: Int): Flow<ActiveZenModes?> {
    return if (AudioStream.supportedStreamTypes.contains(stream)) {
        val audioStream = AudioStream(stream)
        if (canBeBlockedByZenMode(audioStream)) {
            activeModesBlockingStream(audioStream)
        } else {
            flowOf(null)
        }
    } else {
        flowOf(null)
    }
}
