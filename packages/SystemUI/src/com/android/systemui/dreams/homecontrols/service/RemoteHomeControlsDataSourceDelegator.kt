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

package com.android.systemui.dreams.homecontrols.service

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dreams.DreamLogger
import com.android.systemui.dreams.homecontrols.dagger.HomeControlsRemoteServiceComponent
import com.android.systemui.dreams.homecontrols.shared.model.HomeControlsComponentInfo
import com.android.systemui.dreams.homecontrols.shared.model.HomeControlsDataSource
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.DreamLog
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.service.ObservableServiceConnection
import com.android.systemui.util.service.PersistentConnectionManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Queries a remote service for [HomeControlsComponentInfo] necessary to show the home controls
 * dream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class RemoteHomeControlsDataSourceDelegator
@Inject
constructor(
    @Background bgScope: CoroutineScope,
    serviceFactory: HomeControlsRemoteServiceComponent.Factory,
    @DreamLog logBuffer: LogBuffer,
    dumpManager: DumpManager,
) : HomeControlsDataSource, FlowDumperImpl(dumpManager) {
    private val logger = DreamLogger(logBuffer, TAG)

    private val connectionManager: PersistentConnectionManager<HomeControlsRemoteProxy> by lazy {
        serviceFactory.create(callback).connectionManager
    }

    private val proxyState =
        MutableStateFlow<HomeControlsRemoteProxy?>(null)
            .apply {
                subscriptionCount
                    .map { it > 0 }
                    .dropWhile { !it }
                    .distinctUntilChanged()
                    .onEach { active ->
                        logger.d({ "Remote service connection active: $bool1" }) { bool1 = active }
                        if (active) {
                            connectionManager.start()
                        } else {
                            connectionManager.stop()
                        }
                    }
                    .launchIn(bgScope)
            }
            .dumpValue("proxyState")

    private val callback: ObservableServiceConnection.Callback<HomeControlsRemoteProxy> =
        object : ObservableServiceConnection.Callback<HomeControlsRemoteProxy> {
            override fun onConnected(
                connection: ObservableServiceConnection<HomeControlsRemoteProxy>?,
                proxy: HomeControlsRemoteProxy,
            ) {
                logger.d("Service connected")
                proxyState.value = proxy
            }

            override fun onDisconnected(
                connection: ObservableServiceConnection<HomeControlsRemoteProxy>?,
                reason: Int,
            ) {
                logger.d({ "Service disconnected with reason $int1" }) { int1 = reason }
                proxyState.value = null
            }
        }

    override val componentInfo: Flow<HomeControlsComponentInfo> =
        proxyState
            .filterNotNull()
            .flatMapLatest { proxy: HomeControlsRemoteProxy -> proxy.componentInfo }
            .dumpWhileCollecting("componentInfo")

    private companion object {
        const val TAG = "HomeControlsRemoteDataSourceDelegator"
    }
}
