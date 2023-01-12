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
 */

package com.android.systemui.statusbar.pipeline.wifi.data.repository

import android.os.Bundle
import androidx.annotation.VisibleForTesting
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.DemoWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Provides the [WifiRepository] interface either through the [DemoWifiRepository] implementation,
 * or the [WifiRepositoryImpl]'s prod implementation, based on the current demo mode value. In this
 * way, downstream clients can all consist of real implementations and not care about which
 * repository is responsible for the data. Graphically:
 *
 * ```
 * RealRepository
 *                 │
 *                 ├──►RepositorySwitcher──►RealInteractor──►RealViewModel
 *                 │
 * DemoRepository
 * ```
 *
 * When demo mode turns on, every flow will [flatMapLatest] to the current provider's version of
 * that flow.
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class WifiRepositorySwitcher
@Inject
constructor(
    private val realImpl: RealWifiRepository,
    private val demoImpl: DemoWifiRepository,
    private val demoModeController: DemoModeController,
    @Application scope: CoroutineScope,
) : WifiRepository {
    private val isDemoMode =
        conflatedCallbackFlow {
                val callback =
                    object : DemoMode {
                        override fun dispatchDemoCommand(command: String?, args: Bundle?) {
                            // Don't care
                        }

                        override fun onDemoModeStarted() {
                            demoImpl.startProcessingCommands()
                            trySend(true)
                        }

                        override fun onDemoModeFinished() {
                            demoImpl.stopProcessingCommands()
                            trySend(false)
                        }
                    }

                demoModeController.addCallback(callback)
                awaitClose { demoModeController.removeCallback(callback) }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), demoModeController.isInDemoMode)

    @VisibleForTesting
    val activeRepo =
        isDemoMode
            .mapLatest { isDemoMode ->
                if (isDemoMode) {
                    demoImpl
                } else {
                    realImpl
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl)

    override val isWifiEnabled: StateFlow<Boolean> =
        activeRepo
            .flatMapLatest { it.isWifiEnabled }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl.isWifiEnabled.value)

    override val isWifiDefault: StateFlow<Boolean> =
        activeRepo
            .flatMapLatest { it.isWifiDefault }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl.isWifiDefault.value)

    override val wifiNetwork: StateFlow<WifiNetworkModel> =
        activeRepo
            .flatMapLatest { it.wifiNetwork }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl.wifiNetwork.value)

    override val wifiActivity: StateFlow<DataActivityModel> =
        activeRepo
            .flatMapLatest { it.wifiActivity }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl.wifiActivity.value)
}
