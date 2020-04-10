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

package com.android.systemui.controls.controller

import android.service.controls.Control
import android.service.controls.DeviceTypes

/**
 * Stores basic information about a [Control] to persist and keep track of favorites.
 *
 * The identifier of this [Control] is the [controlId], and is only unique per app. The other
 * fields are there for persistence. In this way, basic information can be shown to the user
 * before the service has to report on the status.
 *
 * @property controlId unique identifier for this [Control].
 * @property controlTitle last title reported for this [Control].
 * @property controlSubtitle last subtitle reported for this [Control].
 * @property deviceType last reported type for this [Control].
 */
data class ControlInfo(
    val controlId: String,
    val controlTitle: CharSequence,
    val controlSubtitle: CharSequence,
    @DeviceTypes.DeviceType val deviceType: Int
) {

    companion object {
        private const val SEPARATOR = ":"
        fun fromControl(control: Control): ControlInfo {
            return ControlInfo(
                    control.controlId,
                    control.title,
                    control.subtitle,
                    control.deviceType
            )
        }
    }

    /**
     * Returns a [String] representation of the fields separated using [SEPARATOR].
     *
     * @return a [String] representation of `this`
     */
    override fun toString(): String {
        return "$SEPARATOR$controlId$SEPARATOR$controlTitle$SEPARATOR$deviceType"
    }
}
