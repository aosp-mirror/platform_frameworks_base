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

package com.android.systemui.accessibility.hearingaid

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

enum class HearingDevicesUiEvent(private val id: Int) : UiEventLogger.UiEventEnum {

    @UiEvent(doc = "Hearing devices dialog is shown") HEARING_DEVICES_DIALOG_SHOW(1848),
    @UiEvent(doc = "Pair new device") HEARING_DEVICES_PAIR(1849),
    @UiEvent(doc = "Connect to the device") HEARING_DEVICES_CONNECT(1850),
    @UiEvent(doc = "Disconnect from the device") HEARING_DEVICES_DISCONNECT(1851),
    @UiEvent(doc = "Set the device as active device") HEARING_DEVICES_SET_ACTIVE(1852),
    @UiEvent(doc = "Click on the device gear to enter device detail page")
    HEARING_DEVICES_GEAR_CLICK(1853),
    @UiEvent(doc = "Select a preset from preset spinner") HEARING_DEVICES_PRESET_SELECT(1854),
    @UiEvent(doc = "Click on related tool") HEARING_DEVICES_RELATED_TOOL_CLICK(1856);

    override fun getId(): Int = this.id
}
