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

package com.android.systemui.shade.data.repository

import android.content.IntentFilter
import android.os.UserHandle
import android.safetycenter.SafetyCenterManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.privacy.PrivacyConfig
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

interface PrivacyChipRepository {
    /** Whether or not the Safety Center is enabled. */
    val isSafetyCenterEnabled: StateFlow<Boolean>

    /** The list of PrivacyItems to be displayed by the privacy chip. */
    val privacyItems: StateFlow<List<PrivacyItem>>

    /** Whether or not mic & camera indicators are enabled in the device privacy config. */
    val isMicCameraIndicationEnabled: StateFlow<Boolean>

    /** Whether or not location indicators are enabled in the device privacy config. */
    val isLocationIndicationEnabled: StateFlow<Boolean>
}

@SysUISingleton
class PrivacyChipRepositoryImpl
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val privacyConfig: PrivacyConfig,
    private val privacyItemController: PrivacyItemController,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    broadcastDispatcher: BroadcastDispatcher,
    private val safetyCenterManager: SafetyCenterManager,
) : PrivacyChipRepository {
    override val isSafetyCenterEnabled: StateFlow<Boolean> =
        broadcastDispatcher
            .broadcastFlow(
                filter =
                    IntentFilter().apply {
                        addAction(SafetyCenterManager.ACTION_SAFETY_CENTER_ENABLED_CHANGED)
                    },
                user = UserHandle.SYSTEM,
                map = { _, _ -> safetyCenterManager.isSafetyCenterEnabled }
            )
            .onStart { emit(safetyCenterManager.isSafetyCenterEnabled) }
            .flowOn(backgroundDispatcher)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    override val privacyItems: StateFlow<List<PrivacyItem>> =
        conflatedCallbackFlow {
                val callback =
                    object : PrivacyItemController.Callback {
                        override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
                            trySend(privacyItems)
                        }
                    }
                privacyItemController.addCallback(callback)
                awaitClose { privacyItemController.removeCallback(callback) }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    override val isMicCameraIndicationEnabled: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : PrivacyConfig.Callback {
                        override fun onFlagMicCameraChanged(flag: Boolean) {
                            trySend(flag)
                        }
                    }
                privacyConfig.addCallback(callback)
                awaitClose { privacyConfig.removeCallback(callback) }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = privacyItemController.micCameraAvailable,
            )

    override val isLocationIndicationEnabled: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : PrivacyConfig.Callback {
                        override fun onFlagLocationChanged(flag: Boolean) {
                            trySend(flag)
                        }
                    }
                privacyConfig.addCallback(callback)
                awaitClose { privacyConfig.removeCallback(callback) }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = privacyItemController.locationAvailable,
            )
}
