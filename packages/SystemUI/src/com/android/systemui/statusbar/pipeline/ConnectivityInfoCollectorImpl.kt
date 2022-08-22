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

package com.android.systemui.statusbar.pipeline

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.wifi.data.repository.NetworkCapabilitiesRepo
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted.Companion.Lazily
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The real implementation of [ConnectivityInfoCollector] that will collect information from all the
 * relevant connectivity callbacks and compile it into [rawConnectivityInfoFlow].
 */
@SysUISingleton
class ConnectivityInfoCollectorImpl @Inject constructor(
        networkCapabilitiesRepo: NetworkCapabilitiesRepo,
        @Application scope: CoroutineScope,
) : ConnectivityInfoCollector {
    override val rawConnectivityInfoFlow: StateFlow<RawConnectivityInfo> =
            // TODO(b/238425913): Collect all the separate flows for individual raw information into
            //   this final flow.
            networkCapabilitiesRepo.dataStream
                    .map {
                        RawConnectivityInfo(networkCapabilityInfo = it)
                    }
                    .stateIn(scope, started = Lazily, initialValue = RawConnectivityInfo())
}
