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

package com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.model

import android.telephony.Annotation

/**
 * Model for demo wifi commands, ported from [NetworkControllerImpl]
 *
 * Nullable fields represent optional command line arguments
 */
sealed interface FakeWifiEventModel {
    data class Wifi(
        val level: Int?,
        @Annotation.DataActivityType val activity: Int,
        val ssid: String?,
        val validated: Boolean?,
    ) : FakeWifiEventModel

    data class CarrierMerged(
        val subscriptionId: Int,
        val level: Int,
        val numberOfLevels: Int,
        @Annotation.DataActivityType val activity: Int,
    ) : FakeWifiEventModel

    object WifiDisabled : FakeWifiEventModel
}
