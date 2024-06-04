/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.android.systemui.animation.Expandable
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.res.R
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.RingerModeTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class MuteQuickAffordanceConfig
@Inject
constructor(
    private val context: Context,
    private val userTracker: UserTracker,
    private val userFileManager: UserFileManager,
    private val ringerModeTracker: RingerModeTracker,
    private val audioManager: AudioManager,
    @Application private val coroutineScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : KeyguardQuickAffordanceConfig {

    private var previousNonSilentMode: Int = DEFAULT_LAST_NON_SILENT_VALUE

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.MUTE

    override fun pickerName(): String = context.getString(R.string.volume_ringer_status_silent)

    override val pickerIconResourceId: Int = R.drawable.ic_notifications_silence

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        ringerModeTracker.ringerModeInternal
            .asFlow()
            .onStart { getLastNonSilentRingerMode() }
            .distinctUntilChanged()
            .onEach { mode ->
                // only remember last non-SILENT ringer mode
                if (mode != null && mode != AudioManager.RINGER_MODE_SILENT) {
                    previousNonSilentMode = mode
                }
            }
            .map { mode ->
                val (activationState, contentDescriptionRes) =
                    when {
                        audioManager.isVolumeFixed ->
                            ActivationState.NotSupported to R.string.volume_ringer_hint_mute
                        mode == AudioManager.RINGER_MODE_SILENT ->
                            ActivationState.Active to R.string.volume_ringer_hint_unmute
                        else -> ActivationState.Inactive to R.string.volume_ringer_hint_mute
                    }

                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    Icon.Resource(
                        R.drawable.ic_notifications_silence,
                        ContentDescription.Resource(contentDescriptionRes),
                    ),
                    activationState,
                )
            }
            .flowOn(backgroundDispatcher)

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        coroutineScope.launch(backgroundDispatcher) {
            val newRingerMode: Int
            val currentRingerMode = audioManager.ringerModeInternal
            if (currentRingerMode == AudioManager.RINGER_MODE_SILENT) {
                newRingerMode = previousNonSilentMode
            } else {
                previousNonSilentMode = currentRingerMode
                newRingerMode = AudioManager.RINGER_MODE_SILENT
            }

            if (currentRingerMode != newRingerMode) {
                audioManager.ringerModeInternal = newRingerMode
            }
        }
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
    }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState =
        withContext(backgroundDispatcher) {
            if (audioManager.isVolumeFixed) {
                KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
            } else {
                KeyguardQuickAffordanceConfig.PickerScreenState.Default()
            }
        }

    /**
     * Gets the last non-silent ringer mode from shared-preferences if it exists. This is cached by
     * [MuteQuickAffordanceCoreStartable] while this affordance is selected
     */
    private suspend fun getLastNonSilentRingerMode(): Int =
        withContext(backgroundDispatcher) {
            userFileManager
                .getSharedPreferences(
                    MUTE_QUICK_AFFORDANCE_PREFS_FILE_NAME,
                    Context.MODE_PRIVATE,
                    userTracker.userId
                )
                .getInt(
                    LAST_NON_SILENT_RINGER_MODE_KEY,
                    ringerModeTracker.ringerModeInternal.value ?: DEFAULT_LAST_NON_SILENT_VALUE
                )
        }

    private fun <T> LiveData<T>.asFlow(): Flow<T?> =
        conflatedCallbackFlow {
                val observer = Observer { value: T -> trySend(value) }
                observeForever(observer)
                send(value)
                awaitClose { removeObserver(observer) }
            }
            .flowOn(mainDispatcher)

    companion object {
        const val LAST_NON_SILENT_RINGER_MODE_KEY = "key_last_non_silent_ringer_mode"
        const val MUTE_QUICK_AFFORDANCE_PREFS_FILE_NAME = "quick_affordance_mute_ringer_mode_cache"
        private const val DEFAULT_LAST_NON_SILENT_VALUE = AudioManager.RINGER_MODE_NORMAL
    }
}
