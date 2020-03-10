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

package com.android.systemui.controls.ui

import android.annotation.MainThread
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.service.controls.DeviceTypes
import android.service.controls.templates.TemperatureControlTemplate
import android.util.ArrayMap
import android.util.SparseArray

import com.android.systemui.R

data class IconState(val disabledResourceId: Int, val enabledResourceId: Int) {
    operator fun get(state: Boolean): Int {
        return if (state) {
            enabledResourceId
        } else {
            disabledResourceId
        }
    }
}

data class RenderInfo(val icon: Drawable, val foreground: Int, val background: Int) {

    companion object {
        const val APP_ICON_ID = -1
        private val iconMap = SparseArray<Drawable>()
        private val appIconMap = ArrayMap<ComponentName, Drawable>()

        @MainThread
        fun lookup(
            context: Context,
            componentName: ComponentName,
            deviceType: Int,
            enabled: Boolean,
            offset: Int = 0
        ): RenderInfo {
            val (fg, bg) = deviceColorMap.getValue(deviceType)

            val iconKey = if (offset > 0) {
                deviceType * BUCKET_SIZE + offset
            } else deviceType

            val iconState = deviceIconMap.getValue(iconKey)
            val resourceId = iconState[enabled]
            var icon: Drawable? = null
            if (resourceId == APP_ICON_ID) {
                icon = appIconMap.get(componentName)
                if (icon == null) {
                    icon = context.resources
                        .getDrawable(R.drawable.ic_device_unknown_gm2_24px, null)
                    appIconMap.put(componentName, icon)
                }
            } else {
                icon = iconMap.get(resourceId)
                if (icon == null) {
                    icon = context.resources.getDrawable(resourceId, null)
                    iconMap.put(resourceId, icon)
                }
            }
            return RenderInfo(icon!!, fg, bg)
        }

        fun registerComponentIcon(componentName: ComponentName, icon: Drawable) {
            appIconMap.put(componentName, icon)
        }

        fun clearCache() {
            iconMap.clear()
            appIconMap.clear()
        }
    }
}

private const val BUCKET_SIZE = 1000
private const val THERMOSTAT_RANGE = DeviceTypes.TYPE_THERMOSTAT * BUCKET_SIZE

private val deviceColorMap = mapOf<Int, Pair<Int, Int>>(
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_HEAT) to
        Pair(R.color.thermo_heat_foreground, R.color.thermo_heat_background),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_COOL) to
        Pair(R.color.thermo_cool_foreground, R.color.thermo_cool_background),
    DeviceTypes.TYPE_LIGHT to Pair(R.color.light_foreground, R.color.light_background)
).withDefault {
        Pair(R.color.control_foreground, R.color.control_background)
}

private val deviceIconMap = mapOf<Int, IconState>(
    THERMOSTAT_RANGE to IconState(
        R.drawable.ic_device_thermostat_gm2_24px,
        R.drawable.ic_device_thermostat_gm2_24px
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_OFF) to IconState(
        R.drawable.ic_device_thermostat_gm2_24px,
        R.drawable.ic_device_thermostat_gm2_24px
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_HEAT) to IconState(
        R.drawable.ic_device_thermostat_gm2_24px,
        R.drawable.ic_device_thermostat_gm2_24px
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_COOL) to IconState(
        R.drawable.ic_device_thermostat_gm2_24px,
        R.drawable.ic_device_thermostat_gm2_24px
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_HEAT_COOL) to IconState(
        R.drawable.ic_device_thermostat_gm2_24px,
        R.drawable.ic_device_thermostat_gm2_24px
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_ECO) to IconState(
        R.drawable.ic_device_thermostat_gm2_24px,
        R.drawable.ic_device_thermostat_gm2_24px
    ),
    DeviceTypes.TYPE_THERMOSTAT to IconState(
        R.drawable.ic_device_thermostat_gm2_24px,
        R.drawable.ic_device_thermostat_gm2_24px
    ),
    DeviceTypes.TYPE_LIGHT to IconState(
        R.drawable.ic_light_off_gm2_24px,
        R.drawable.ic_lightbulb_outline_gm2_24px
    ),
    DeviceTypes.TYPE_CAMERA to IconState(
        R.drawable.ic_videocam_gm2_24px,
        R.drawable.ic_videocam_gm2_24px
    ),
    DeviceTypes.TYPE_LOCK to IconState(
        R.drawable.ic_lock_open_gm2_24px,
        R.drawable.ic_lock_gm2_24px
    ),
    DeviceTypes.TYPE_SWITCH to IconState(
        R.drawable.ic_switches_gm2_24px,
        R.drawable.ic_switches_gm2_24px
    ),
    DeviceTypes.TYPE_OUTLET to IconState(
        R.drawable.ic_power_off_gm2_24px,
        R.drawable.ic_power_gm2_24px
    ),
    DeviceTypes.TYPE_VACUUM to IconState(
        R.drawable.ic_vacuum_gm2_24px,
        R.drawable.ic_vacuum_gm2_24px
    ),
    DeviceTypes.TYPE_MOP to IconState(
        R.drawable.ic_vacuum_gm2_24px,
        R.drawable.ic_vacuum_gm2_24px
    ),
    DeviceTypes.TYPE_ROUTINE to IconState(
        RenderInfo.APP_ICON_ID,
        RenderInfo.APP_ICON_ID
    )
).withDefault {
    IconState(
        R.drawable.ic_device_unknown_gm2_24px,
        R.drawable.ic_device_unknown_gm2_24px
    )
}
