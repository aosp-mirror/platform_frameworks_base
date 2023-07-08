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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.shared.model.SettingsClockSize
import com.android.systemui.plugins.ClockId
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@SysUISingleton
class KeyguardClockRepository
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val clockRegistry: ClockRegistry,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    val selectedClockSize: Flow<SettingsClockSize> =
        secureSettings
            .observerFlow(
                names = arrayOf(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK),
                userId = UserHandle.USER_SYSTEM,
            )
            .onStart { emit(Unit) } // Forces an initial update.
            .map { getClockSize() }

    val currentClockId: Flow<ClockId> =
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

    private suspend fun getClockSize(): SettingsClockSize {
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
