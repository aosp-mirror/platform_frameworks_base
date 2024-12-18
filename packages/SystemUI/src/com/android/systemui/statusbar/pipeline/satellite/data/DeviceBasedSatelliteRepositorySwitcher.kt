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

package com.android.systemui.statusbar.pipeline.satellite.data

import android.os.Bundle
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.statusbar.pipeline.satellite.data.demo.DemoDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
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
 * A provider for the [DeviceBasedSatelliteRepository] interface that can choose between the Demo
 * and Prod concrete implementations at runtime. It works by defining a base flow, [activeRepo],
 * which switches based on the latest information from [DemoModeController], and switches every flow
 * in the interface to point to the currently-active provider. This allows us to put the demo mode
 * interface in its own repository, completely separate from the real version, while still using all
 * of the prod implementations for the rest of the pipeline (interactors and onward). Looks
 * something like this:
 * ```
 * RealRepository
 *                 │
 *                 ├──►RepositorySwitcher──►RealInteractor──►RealViewModel
 *                 │
 * DemoRepository
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class DeviceBasedSatelliteRepositorySwitcher
@Inject
constructor(
    private val realImpl: RealDeviceBasedSatelliteRepository,
    private val demoImpl: DemoDeviceBasedSatelliteRepository,
    private val demoModeController: DemoModeController,
    @Application scope: CoroutineScope,
) : DeviceBasedSatelliteRepository {
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
    val activeRepo: StateFlow<DeviceBasedSatelliteRepository> =
        isDemoMode
            .mapLatest { isDemoMode ->
                if (isDemoMode) {
                    demoImpl
                } else {
                    realImpl
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl)

    override val isSatelliteProvisioned: StateFlow<Boolean> =
        activeRepo
            .flatMapLatest { it.isSatelliteProvisioned }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl.isSatelliteProvisioned.value)

    override val connectionState: StateFlow<SatelliteConnectionState> =
        activeRepo
            .flatMapLatest { it.connectionState }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl.connectionState.value)

    override val signalStrength: StateFlow<Int> =
        activeRepo
            .flatMapLatest { it.signalStrength }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realImpl.signalStrength.value)

    override val isSatelliteAllowedForCurrentLocation: StateFlow<Boolean> =
        activeRepo
            .flatMapLatest { it.isSatelliteAllowedForCurrentLocation }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                realImpl.isSatelliteAllowedForCurrentLocation.value
            )
}
