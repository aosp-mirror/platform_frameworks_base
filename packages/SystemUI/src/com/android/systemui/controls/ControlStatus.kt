/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.service.controls.Control
import android.service.controls.DeviceTypes

interface ControlInterface {
    val favorite: Boolean
    val component: ComponentName
    val controlId: String
    val title: CharSequence
    val subtitle: CharSequence
    val removed: Boolean
        get() = false
    val customIcon: Icon?
    @DeviceTypes.DeviceType val deviceType: Int
}

data class ControlStatus(
    val control: Control,
    override val component: ComponentName,
    override var favorite: Boolean,
    override val removed: Boolean = false
) : ControlInterface {
    override val controlId: String
        get() = control.controlId

    override val title: CharSequence
        get() = control.title

    override val subtitle: CharSequence
        get() = control.subtitle

    override val customIcon: Icon?
        get() = control.customIcon

    @DeviceTypes.DeviceType override val deviceType: Int
        get() = control.deviceType
}
