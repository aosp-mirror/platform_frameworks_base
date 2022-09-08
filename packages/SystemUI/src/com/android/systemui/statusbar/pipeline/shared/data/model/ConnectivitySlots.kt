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

package com.android.systemui.statusbar.pipeline.shared.data.model

import android.content.Context
import com.android.internal.R
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/**
 * A container for all the different types of connectivity slots: wifi, mobile, etc.
 */
@SysUISingleton
class ConnectivitySlots @Inject constructor(context: Context) {
    private val airplaneSlot: String = context.getString(R.string.status_bar_airplane)
    private val mobileSlot: String = context.getString(R.string.status_bar_mobile)
    private val wifiSlot: String = context.getString(R.string.status_bar_wifi)
    private val ethernetSlot: String = context.getString(R.string.status_bar_ethernet)

    private val slotByName: Map<String, ConnectivitySlot> = mapOf(
        airplaneSlot to ConnectivitySlot.AIRPLANE,
        mobileSlot to ConnectivitySlot.MOBILE,
        wifiSlot to ConnectivitySlot.WIFI,
        ethernetSlot to ConnectivitySlot.ETHERNET
    )

    /**
     * Given a string name of a slot, returns the instance of [ConnectivitySlot] that it corresponds
     * to, or null if we couldn't find that slot name.
     */
    fun getSlotFromName(slotName: String): ConnectivitySlot? {
        return slotByName[slotName]
    }
}

/** The different types of connectivity slots. */
enum class ConnectivitySlot {
    AIRPLANE,
    ETHERNET,
    MOBILE,
    WIFI,
}
