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

package com.android.systemui.volume.dialog.ringer.ui.viewmodel

import android.media.AudioAttributes
import android.media.AudioManager.RINGER_MODE_NORMAL
import android.media.AudioManager.RINGER_MODE_SILENT
import android.media.AudioManager.RINGER_MODE_VIBRATE
import android.os.VibrationEffect
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.ringer.domain.VolumeDialogRingerInteractor
import com.android.systemui.volume.dialog.ringer.shared.model.VolumeDialogRingerModel
import com.android.systemui.volume.dialog.shared.VolumeDialogLogger
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class VolumeDialogRingerDrawerViewModel
@AssistedInject
constructor(
    @VolumeDialog private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val interactor: VolumeDialogRingerInteractor,
    private val vibrator: VibratorHelper,
    private val volumeDialogLogger: VolumeDialogLogger,
) {

    private val drawerState = MutableStateFlow<RingerDrawerState>(RingerDrawerState.Initial)

    val ringerViewModel: StateFlow<RingerViewModelState> =
        combine(interactor.ringerModel, drawerState) { ringerModel, state ->
                ringerModel.toViewModel(state)
            }
            .flowOn(backgroundDispatcher)
            .stateIn(coroutineScope, SharingStarted.Eagerly, RingerViewModelState.Unavailable)

    // Vibration attributes.
    private val sonificiationVibrationAttributes =
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build()

    fun onRingerButtonClicked(ringerMode: RingerMode) {
        if (drawerState.value is RingerDrawerState.Open) {
            Events.writeEvent(Events.EVENT_RINGER_TOGGLE, ringerMode.value)
            provideTouchFeedback(ringerMode)
            interactor.setRingerMode(ringerMode)
        }
        drawerState.value =
            when (drawerState.value) {
                is RingerDrawerState.Initial -> {
                    RingerDrawerState.Open(ringerMode)
                }
                is RingerDrawerState.Open -> {
                    RingerDrawerState.Closed(ringerMode)
                }
                is RingerDrawerState.Closed -> {
                    RingerDrawerState.Open(ringerMode)
                }
            }
    }

    private fun provideTouchFeedback(ringerMode: RingerMode) {
        when (ringerMode.value) {
            RINGER_MODE_NORMAL -> {
                interactor.scheduleTouchFeedback()
                null
            }
            RINGER_MODE_SILENT -> VibrationEffect.get(VibrationEffect.EFFECT_CLICK)
            RINGER_MODE_VIBRATE -> VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK)
            else -> VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK)
        }?.let { vibrator.vibrate(it, sonificiationVibrationAttributes) }
    }

    private fun VolumeDialogRingerModel.toViewModel(
        drawerState: RingerDrawerState
    ): RingerViewModelState {
        val currentIndex = availableModes.indexOf(currentRingerMode)
        if (currentIndex == -1) {
            volumeDialogLogger.onCurrentRingerModeIsUnsupported(currentRingerMode)
        }
        return if (currentIndex == -1 || isSingleVolume) {
            RingerViewModelState.Unavailable
        } else {
            toButtonViewModel(currentRingerMode, isSelectedButton = true)?.let {
                RingerViewModelState.Available(
                    RingerViewModel(
                        availableButtons = availableModes.map { mode -> toButtonViewModel(mode) },
                        currentButtonIndex = currentIndex,
                        selectedButton = it,
                        drawerState = drawerState,
                    )
                )
            } ?: RingerViewModelState.Unavailable
        }
    }

    private fun VolumeDialogRingerModel.toButtonViewModel(
        ringerMode: RingerMode,
        isSelectedButton: Boolean = false,
    ): RingerButtonViewModel? {
        return when (ringerMode.value) {
            RINGER_MODE_SILENT ->
                RingerButtonViewModel(
                    imageResId = R.drawable.ic_speaker_mute,
                    contentDescriptionResId =
                        if (isSelectedButton) {
                            R.string.volume_ringer_status_silent
                        } else {
                            R.string.volume_ringer_hint_mute
                        },
                    hintLabelResId = R.string.volume_ringer_hint_unmute,
                    ringerMode = ringerMode,
                )
            RINGER_MODE_VIBRATE ->
                RingerButtonViewModel(
                    imageResId = R.drawable.ic_volume_ringer_vibrate,
                    contentDescriptionResId =
                        if (isSelectedButton) {
                            R.string.volume_ringer_status_vibrate
                        } else {
                            R.string.volume_ringer_hint_vibrate
                        },
                    hintLabelResId = R.string.volume_ringer_hint_vibrate,
                    ringerMode = ringerMode,
                )
            RINGER_MODE_NORMAL ->
                when {
                    isMuted && isEnabled ->
                        RingerButtonViewModel(
                            imageResId =
                                if (isSelectedButton) {
                                    R.drawable.ic_speaker_mute
                                } else {
                                    R.drawable.ic_speaker_on
                                },
                            contentDescriptionResId =
                                if (isSelectedButton) {
                                    R.string.volume_ringer_status_normal
                                } else {
                                    R.string.volume_ringer_hint_unmute
                                },
                            hintLabelResId = R.string.volume_ringer_hint_unmute,
                            ringerMode = ringerMode,
                        )

                    availableModes.contains(RingerMode(RINGER_MODE_VIBRATE)) ->
                        RingerButtonViewModel(
                            imageResId = R.drawable.ic_speaker_on,
                            contentDescriptionResId =
                                if (isSelectedButton) {
                                    R.string.volume_ringer_status_normal
                                } else {
                                    R.string.volume_ringer_hint_unmute
                                },
                            hintLabelResId = R.string.volume_ringer_hint_vibrate,
                            ringerMode = ringerMode,
                        )

                    else ->
                        RingerButtonViewModel(
                            imageResId = R.drawable.ic_speaker_on,
                            contentDescriptionResId =
                                if (isSelectedButton) {
                                    R.string.volume_ringer_status_normal
                                } else {
                                    R.string.volume_ringer_hint_unmute
                                },
                            hintLabelResId = R.string.volume_ringer_hint_mute,
                            ringerMode = ringerMode,
                        )
                }
            else -> null
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): VolumeDialogRingerDrawerViewModel
    }
}
