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

import com.android.systemui.statusbar.pipeline.wifi.data.repository.NetworkCapabilityInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface exposing a flow for raw connectivity information. Clients should collect on
 * [rawConnectivityInfoFlow] to get updates on connectivity information.
 *
 * Note: [rawConnectivityInfoFlow] should be a *hot* flow, so that we only create one instance of it
 * and all clients get references to the same flow.
 *
 * This will be used for the new status bar pipeline to compile information we need to display some
 * of the icons in the RHS of the status bar.
 */
interface ConnectivityInfoCollector {
    val rawConnectivityInfoFlow: StateFlow<RawConnectivityInfo>
}

/**
 * An object containing all of the raw connectivity information.
 *
 * Importantly, all the information in this object should not be processed at all (i.e., the data
 * that we receive from callbacks should be piped straight into this object and not be filtered,
 * manipulated, or processed in any way). Instead, any listeners on
 * [ConnectivityInfoCollector.rawConnectivityInfoFlow] can do the processing.
 *
 * This allows us to keep all the processing in one place which is beneficial for logging and
 * debugging purposes.
 */
data class RawConnectivityInfo(
        val networkCapabilityInfo: Map<Int, NetworkCapabilityInfo> = emptyMap(),
)
