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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.os.Bundle
import androidx.annotation.VisibleForTesting
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.DemoMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileConnectionsRepositoryImpl
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * A provider for the [MobileConnectionsRepository] interface that can choose between the Demo and
 * Prod concrete implementations at runtime. It works by defining a base flow, [activeRepo], which
 * switches based on the latest information from [DemoModeController], and switches every flow in
 * the interface to point to the currently-active provider. This allows us to put the demo mode
 * interface in its own repository, completely separate from the real version, while still using all
 * of the prod implementations for the rest of the pipeline (interactors and onward). Looks
 * something like this:
 *
 * ```
 * RealRepository
 *                 │
 *                 ├──►RepositorySwitcher──►RealInteractor──►RealViewModel
 *                 │
 * DemoRepository
 * ```
 *
 * NOTE: because the UI layer for mobile icons relies on a nested-repository structure, it is likely
 * that we will have to drain the subscription list whenever demo mode changes. Otherwise if a real
 * subscription list [1] is replaced with a demo subscription list [1], the view models will not see
 * a change (due to `distinctUntilChanged`) and will not refresh their data providers to the demo
 * implementation.
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileRepositorySwitcher
@Inject
constructor(
    @Application scope: CoroutineScope,
    val realRepository: MobileConnectionsRepositoryImpl,
    val demoMobileConnectionsRepository: DemoMobileConnectionsRepository,
    demoModeController: DemoModeController,
) : MobileConnectionsRepository {

    val isDemoMode: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : DemoMode {
                        override fun dispatchDemoCommand(command: String?, args: Bundle?) {
                            // Nothing, we just care about on/off
                        }

                        override fun onDemoModeStarted() {
                            demoMobileConnectionsRepository.startProcessingCommands()
                            trySend(true)
                        }

                        override fun onDemoModeFinished() {
                            demoMobileConnectionsRepository.stopProcessingCommands()
                            trySend(false)
                        }
                    }

                demoModeController.addCallback(callback)
                awaitClose { demoModeController.removeCallback(callback) }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), demoModeController.isInDemoMode)

    // Convenient definition flow for the currently active repo (based on demo mode or not)
    @VisibleForTesting
    internal val activeRepo: StateFlow<MobileConnectionsRepository> =
        isDemoMode
            .mapLatest { demoMode ->
                if (demoMode) {
                    demoMobileConnectionsRepository
                } else {
                    realRepository
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realRepository)

    override val subscriptions: StateFlow<List<SubscriptionModel>> =
        activeRepo
            .flatMapLatest { it.subscriptions }
            .stateIn(scope, SharingStarted.WhileSubscribed(), realRepository.subscriptions.value)

    override val activeMobileDataSubscriptionId: StateFlow<Int> =
        activeRepo
            .flatMapLatest { it.activeMobileDataSubscriptionId }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                realRepository.activeMobileDataSubscriptionId.value
            )

    override val activeSubChangedInGroupEvent: Flow<Unit> =
        activeRepo.flatMapLatest { it.activeSubChangedInGroupEvent }

    override val defaultDataSubRatConfig: StateFlow<MobileMappings.Config> =
        activeRepo
            .flatMapLatest { it.defaultDataSubRatConfig }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                realRepository.defaultDataSubRatConfig.value
            )

    override val defaultMobileIconMapping: Flow<Map<String, SignalIcon.MobileIconGroup>> =
        activeRepo.flatMapLatest { it.defaultMobileIconMapping }

    override val defaultMobileIconGroup: Flow<SignalIcon.MobileIconGroup> =
        activeRepo.flatMapLatest { it.defaultMobileIconGroup }

    override val defaultMobileNetworkConnectivity: StateFlow<MobileConnectivityModel> =
        activeRepo
            .flatMapLatest { it.defaultMobileNetworkConnectivity }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                realRepository.defaultMobileNetworkConnectivity.value
            )

    override val globalMobileDataSettingChangedEvent: Flow<Unit> =
        activeRepo.flatMapLatest { it.globalMobileDataSettingChangedEvent }

    override fun getRepoForSubId(subId: Int): MobileConnectionRepository {
        if (isDemoMode.value) {
            return demoMobileConnectionsRepository.getRepoForSubId(subId)
        }
        return realRepository.getRepoForSubId(subId)
    }
}
