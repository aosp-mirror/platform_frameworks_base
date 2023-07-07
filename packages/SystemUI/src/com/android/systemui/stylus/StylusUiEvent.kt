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

package com.android.systemui.stylus

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

enum class StylusUiEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "UiEvent for USI low battery notification shown")
    STYLUS_LOW_BATTERY_NOTIFICATION_SHOWN(1298),
    @UiEvent(doc = "UiEvent for USI low battery notification clicked")
    STYLUS_LOW_BATTERY_NOTIFICATION_CLICKED(1299),
    @UiEvent(doc = "UiEvent for USI low battery notification dismissed")
    STYLUS_LOW_BATTERY_NOTIFICATION_DISMISSED(1300),
    @UiEvent(doc = "UIEvent for Toast shown when stylus started charging")
    STYLUS_STARTED_CHARGING(1302),
    @UiEvent(doc = "UIEvent for Toast shown when stylus stopped charging")
    STYLUS_STOPPED_CHARGING(1303),
    @UiEvent(doc = "UIEvent for bluetooth stylus connected") BLUETOOTH_STYLUS_CONNECTED(1304),
    @UiEvent(doc = "UIEvent for bluetooth stylus disconnected") BLUETOOTH_STYLUS_DISCONNECTED(1305),
    @UiEvent(doc = "UIEvent for start of a USI session via battery presence")
    USI_STYLUS_BATTERY_PRESENCE_FIRST_DETECTED(1306),
    @UiEvent(doc = "UIEvent for end of a USI session via battery absence")
    USI_STYLUS_BATTERY_PRESENCE_REMOVED(1307);

    override fun getId() = _id
}
