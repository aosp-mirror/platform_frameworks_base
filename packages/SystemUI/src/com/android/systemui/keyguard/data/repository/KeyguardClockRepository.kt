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

import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch.ClockSize
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.shared.model.SettingsClockSize
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockId
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

interface KeyguardClockRepository {
    /** clock size determined by notificationPanelViewController, LARGE or SMALL */
    val clockSize: StateFlow<Int>

    /** clock size selected in picker, DYNAMIC or SMALL */
    val selectedClockSize: Flow<SettingsClockSize>

    /** clock id, selected from clock carousel in wallpaper picker */
    val currentClockId: Flow<ClockId>

    val currentClock: StateFlow<ClockController?>

    val previewClock: StateFlow<ClockController>

    val clockEventController: ClockEventController
    fun setClockSize(@ClockSize size: Int)
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
) : KeyguardClockRepository {

    /** Receive SMALL or LARGE clock should be displayed on keyguard. */
    private val _clockSize: MutableStateFlow<Int> = MutableStateFlow(LARGE)
    override val clockSize: StateFlow<Int> = _clockSize.asStateFlow()

    override fun setClockSize(size: Int) {
        _clockSize.value = size
    }

    override val selectedClockSize: Flow<SettingsClockSize> =
        secureSettings
            .observerFlow(
                names = arrayOf(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK),
                userId = UserHandle.USER_SYSTEM,
            )
            .onStart { emit(Unit) } // Forces an initial update.
            .map { getClockSize() }

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
            .distinctUntilChanged()

    override val currentClock: StateFlow<ClockController?> =
        currentClockId
            .map { clockRegistry.createCurrentClock() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = clockRegistry.createCurrentClock()
            )

    override val previewClock: StateFlow<ClockController> =
        currentClockId
            .map { clockRegistry.createCurrentClock() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = clockRegistry.createCurrentClock()
            )

    @VisibleForTesting
    suspend fun getClockSize(): SettingsClockSize {
        return withContext(backgroundDispatcher) {
            if (
                secureSettings.getIntForUser(
                    Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK,
                    1,
                    UserHandle.USER_CURRENT
                ) == 1
            ) {
                SettingsClockSize.DYNAMIC
            } else {
                SettingsClockSize.SMALL
            }
        }
    }
}
