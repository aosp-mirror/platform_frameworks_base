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

package com.android.systemui.keyguard.data.repository

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.android.keyguard.ClockEventController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockId
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

interface KeyguardClockRepository {
    /**
     * clock size determined by notificationPanelViewController, LARGE or SMALL
     *
     * @deprecated When scene container flag is on use clockSize from domain level.
     */
    val clockSize: StateFlow<ClockSize>

    /** clock size selected in picker, DYNAMIC or SMALL */
    val selectedClockSize: StateFlow<ClockSizeSetting>

    /** clock id, selected from clock carousel in wallpaper picker */
    val currentClockId: Flow<ClockId>

    val currentClock: StateFlow<ClockController?>

    val previewClock: Flow<ClockController>

    val clockEventController: ClockEventController

    val shouldForceSmallClock: Boolean

    fun setClockSize(size: ClockSize)
}

@SysUISingleton
class KeyguardClockRepositoryImpl
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val clockRegistry: ClockRegistry,
    override val clockEventController: ClockEventController,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Application private val applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    private val featureFlags: FeatureFlagsClassic,
) : KeyguardClockRepository {

    /** Receive SMALL or LARGE clock should be displayed on keyguard. */
    private val _clockSize: MutableStateFlow<ClockSize> = MutableStateFlow(ClockSize.LARGE)
    override val clockSize: StateFlow<ClockSize> = _clockSize.asStateFlow()

    override fun setClockSize(size: ClockSize) {
        SceneContainerFlag.assertInLegacyMode()
        _clockSize.value = size
    }

    override val selectedClockSize: StateFlow<ClockSizeSetting> =
        secureSettings
            .observerFlow(
                names = arrayOf(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK),
                userId = UserHandle.USER_SYSTEM,
            )
            .onStart { emit(Unit) } // Forces an initial update.
            .map { withContext(backgroundDispatcher) { getClockSize() } }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = getClockSize()
            )

    override val currentClockId: Flow<ClockId> =
        callbackFlow {
                fun send() {
                    trySend(clockRegistry.currentClockId)
                }

                val listener =
                    object : ClockRegistry.ClockChangeListener {
                        override fun onCurrentClockChanged() {
                            send()
                        }
                    }
                clockRegistry.registerClockChangeListener(listener)
                send()
                awaitClose { clockRegistry.unregisterClockChangeListener(listener) }
            }
            .mapNotNull { it }

    override val currentClock: StateFlow<ClockController?> =
        currentClockId
            .map {
                clockEventController.clock = clockRegistry.createCurrentClock()
                clockEventController.clock
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = clockRegistry.createCurrentClock()
            )

    override val previewClock: Flow<ClockController> =
        currentClockId.map {
            // We should create a new instance for each collect call
            // cause in preview, the same clock will be attached to different view
            // at the same time
            clockRegistry.createCurrentClock()
        }

    override val shouldForceSmallClock: Boolean
        get() =
            featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE) &&
                // True on small landscape screens
                applicationContext.resources.getBoolean(R.bool.force_small_clock_on_lockscreen)

    private fun getClockSize(): ClockSizeSetting {
        return ClockSizeSetting.fromSettingValue(
            secureSettings.getIntForUser(
                Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK,
                /* defaultValue= */ 1,
                UserHandle.USER_CURRENT
            )
        )
    }
}
