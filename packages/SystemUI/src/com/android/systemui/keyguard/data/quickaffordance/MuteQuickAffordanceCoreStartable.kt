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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.Observer
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.RingerModeTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Store previous non-silent Ringer Mode into shared prefs to be used for Mute Lockscreen Shortcut
 */
@SysUISingleton
class MuteQuickAffordanceCoreStartable @Inject constructor(
    private val featureFlags: FeatureFlags,
    private val userTracker: UserTracker,
    private val ringerModeTracker: RingerModeTracker,
    private val userFileManager: UserFileManager,
    private val keyguardQuickAffordanceRepository: KeyguardQuickAffordanceRepository,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : CoreStartable {

    private val observer = Observer(this::updateLastNonSilentRingerMode)

    override fun start() {
        if (!featureFlags.isEnabled(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES)) return

        // only listen to ringerModeInternal changes when Mute is one of the selected affordances
        keyguardQuickAffordanceRepository
            .selections
            .map { selections ->
                // determines if Mute is selected in any lockscreen shortcut position
                val muteSelected: Boolean = selections.values.any { configList ->
                    configList.any { config ->
                        config.key == BuiltInKeyguardQuickAffordanceKeys.MUTE
                    }
                }
                if (muteSelected) {
                    ringerModeTracker.ringerModeInternal.observeForever(observer)
                } else {
                    ringerModeTracker.ringerModeInternal.removeObserver(observer)
                }
            }
            .launchIn(coroutineScope)
    }

    private fun updateLastNonSilentRingerMode(lastRingerMode: Int) {
        coroutineScope.launch(backgroundDispatcher) {
            if (AudioManager.RINGER_MODE_SILENT != lastRingerMode) {
                userFileManager.getSharedPreferences(
                        MuteQuickAffordanceConfig.MUTE_QUICK_AFFORDANCE_PREFS_FILE_NAME,
                        Context.MODE_PRIVATE,
                        userTracker.userId
                )
                .edit()
                .putInt(MuteQuickAffordanceConfig.LAST_NON_SILENT_RINGER_MODE_KEY, lastRingerMode)
                .apply()
            }
        }
    }
}