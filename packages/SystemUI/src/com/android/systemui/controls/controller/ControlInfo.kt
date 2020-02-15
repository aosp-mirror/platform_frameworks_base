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

import android.content.ComponentName
import android.service.controls.DeviceTypes
import android.util.Log

/**
 * Stores basic information about a [Control] to persist and keep track of favorites.
 *
 * The identifier of this [Control] is the combination of [component] and [controlId]. The other
 * two fields are there for persistence. In this way, basic information can be shown to the user
 * before the service has to report on the status.
 *
 * @property component the name of the component that provides the [Control].
 * @property controlId unique (for the given [component]) identifier for this [Control].
 * @property controlTitle last title reported for this [Control].
 * @property deviceType last reported type for this [Control].
 */
data class ControlInfo(
    val component: ComponentName,
    val controlId: String,
    val controlTitle: CharSequence,
    @DeviceTypes.DeviceType val deviceType: Int
) {

    companion object {
        private const val TAG = "ControlInfo"
        private const val SEPARATOR = ":"

        /**
         * Creates a [ControlInfo] from a [SEPARATOR] separated list of fields.
         *
         * @param separator fields of a [ControlInfo] separated by [SEPARATOR]
         * @return a [ControlInfo] or `null` if there was an error.
         * @see [ControlInfo.toString]
         */
        fun createFromString(string: String): ControlInfo? {
            val parts = string.split(SEPARATOR)
            val component = ComponentName.unflattenFromString(parts[0])
            if (parts.size != 4 || component == null) {
                Log.e(TAG, "Cannot parse ControlInfo from $string")
                return null
            }
            val type = try {
                parts[3].toInt()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot parse deviceType from ${parts[3]}")
                return null
            }
            return ControlInfo(
                    component,
                    parts[1],
                    parts[2],
                    if (DeviceTypes.validDeviceType(type)) type else DeviceTypes.TYPE_UNKNOWN)
        }
    }

    /**
     * Returns a [String] representation of the fields separated using [SEPARATOR].
     *
     * @return a [String] representation of `this`
     */
    override fun toString(): String {
        return component.flattenToString() +
                "$SEPARATOR$controlId$SEPARATOR$controlTitle$SEPARATOR$deviceType"
    }

    class Builder {
        lateinit var componentName: ComponentName
        lateinit var controlId: String
        lateinit var controlTitle: CharSequence
        var deviceType: Int = DeviceTypes.TYPE_UNKNOWN

        fun build() = ControlInfo(componentName, controlId, controlTitle, deviceType)
    }
}