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

package com.android.systemui.statusbar.pipeline.shared.data.repository

import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fake implementation of [ConnectivityRepository] exposing set methods for all the flows. */
class FakeConnectivityRepository : ConnectivityRepository {
    private val _forceHiddenIcons: MutableStateFlow<Set<ConnectivitySlot>> =
        MutableStateFlow(emptySet())
    override val forceHiddenSlots: StateFlow<Set<ConnectivitySlot>> = _forceHiddenIcons

    override val defaultConnections: MutableStateFlow<DefaultConnectionModel> =
        MutableStateFlow(DefaultConnectionModel())

    override val vcnSubId: MutableStateFlow<Int?> = MutableStateFlow(null)

    fun setForceHiddenIcons(hiddenIcons: Set<ConnectivitySlot>) {
        _forceHiddenIcons.value = hiddenIcons
    }

    /**
     * Convenience for setting mobile data connected, disconnected, or validated. Defaults to
     * setting mobile connected && validated, since the default state is disconnected && not
     * validated
     */
    @JvmOverloads
    fun setMobileConnected(
        default: Boolean = true,
        validated: Boolean = true,
    ) {
        defaultConnections.value =
            DefaultConnectionModel(
                mobile = DefaultConnectionModel.Mobile(default),
                isValidated = validated,
            )
    }

    /** Similar convenience method for ethernet */
    @JvmOverloads
    fun setEthernetConnected(
        default: Boolean = true,
        validated: Boolean = true,
    ) {
        defaultConnections.value =
            DefaultConnectionModel(
                ethernet = DefaultConnectionModel.Ethernet(default),
                isValidated = validated,
            )
    }

    @JvmOverloads
    fun setWifiConnected(
        default: Boolean = true,
        validated: Boolean = true,
    ) {
        defaultConnections.value =
            DefaultConnectionModel(
                wifi = DefaultConnectionModel.Wifi(default),
                isValidated = validated,
            )
    }
}
