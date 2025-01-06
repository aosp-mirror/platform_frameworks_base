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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.telephony.CarrierConfigManager
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig

/**
 * Meant to be the source of truth regarding CarrierConfigs. These are configuration objects defined
 * on a per-subscriptionId basis, and do not trigger a device configuration event.
 *
 * Designed to supplant [com.android.systemui.util.CarrierConfigTracker].
 *
 * See [SystemUiCarrierConfig] for details on how to add carrier config keys to be tracked
 */
interface CarrierConfigRepository {
    /**
     * Start this repository observing broadcasts for **all** carrier configuration updates. Must be
     * called in order to keep SystemUI in sync with [CarrierConfigManager].
     */
    suspend fun startObservingCarrierConfigUpdates()

    /** Gets a cached [SystemUiCarrierConfig], or creates a new one which will track the defaults */
    fun getOrCreateConfigForSubId(subId: Int): SystemUiCarrierConfig
}
