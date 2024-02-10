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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.os.UserHandle
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

/** Repository class responsible for managing the Bluetooth Auto-On feature settings. */
// TODO(b/316822488): Handle multi-user
@SysUISingleton
class BluetoothAutoOnRepository
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val userRepository: UserRepository,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    // Flow representing the auto on setting value
    internal val getValue: Flow<Int> =
        secureSettings
            .observerFlow(UserHandle.USER_SYSTEM, SETTING_NAME)
            .onStart { emit(Unit) }
            .map {
                if (userRepository.getSelectedUserInfo().id != UserHandle.USER_SYSTEM) {
                    Log.i(TAG, "Current user is not USER_SYSTEM. Multi-user is not supported")
                    return@map UNSET
                }
                secureSettings.getIntForUser(SETTING_NAME, UNSET, UserHandle.USER_SYSTEM)
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0))

    /**
     * Checks if the auto on setting value is ever set for the current user.
     *
     * @return `true` if the setting value is not UNSET, `false` otherwise.
     */
    suspend fun isValuePresent(): Boolean =
        withContext(backgroundDispatcher) {
            if (userRepository.getSelectedUserInfo().id != UserHandle.USER_SYSTEM) {
                Log.i(TAG, "Current user is not USER_SYSTEM. Multi-user is not supported")
                false
            } else {
                secureSettings.getIntForUser(SETTING_NAME, UNSET, UserHandle.USER_SYSTEM) != UNSET
            }
        }

    /**
     * Sets the Bluetooth Auto-On setting value for the current user.
     *
     * @param value The new setting value to be applied.
     */
    suspend fun setValue(value: Int) {
        withContext(backgroundDispatcher) {
            if (userRepository.getSelectedUserInfo().id != UserHandle.USER_SYSTEM) {
                Log.i(TAG, "Current user is not USER_SYSTEM. Multi-user is not supported")
            } else {
                secureSettings.putIntForUser(SETTING_NAME, value, UserHandle.USER_SYSTEM)
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothAutoOnRepository"
        const val SETTING_NAME = "bluetooth_automatic_turn_on"
        const val UNSET = -1
    }
}
