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

package com.android.systemui.controls.settings

import android.provider.Settings
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.UserSettingObserver
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * This implementation uses an `@Application` [CoroutineScope] to provide hot flows for the values
 * of the tracked settings.
 */
@SysUISingleton
class ControlsSettingsRepositoryImpl
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val userRepository: UserRepository,
    private val secureSettings: SecureSettings
) : ControlsSettingsRepository {

    override val canShowControlsInLockscreen =
        makeFlowForSetting(Settings.Secure.LOCKSCREEN_SHOW_CONTROLS)

    override val allowActionOnTrivialControlsInLockscreen =
        makeFlowForSetting(Settings.Secure.LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun makeFlowForSetting(setting: String): StateFlow<Boolean> {
        return userRepository.selectedUserInfo
            .distinctUntilChanged()
            .flatMapLatest { userInfo ->
                conflatedCallbackFlow {
                        val observer =
                            object :
                                UserSettingObserver(secureSettings, null, setting, userInfo.id) {
                                override fun handleValueChanged(
                                    value: Int,
                                    observedChange: Boolean
                                ) {
                                    trySend(value == 1)
                                }
                            }
                        observer.isListening = true
                        trySend(observer.value == 1)
                        awaitClose { observer.isListening = false }
                    }
                    .flowOn(backgroundDispatcher)
                    .distinctUntilChanged()
            }
            .stateIn(
                scope,
                started = SharingStarted.Eagerly,
                // When the observer starts listening, the flow will emit the current value
                // so the initialValue here is irrelevant.
                initialValue = false,
            )
    }
}
