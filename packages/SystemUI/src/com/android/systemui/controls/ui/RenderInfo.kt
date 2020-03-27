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

import android.annotation.ColorRes
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

data class RenderInfo(
    val icon: Drawable,
    val foreground: Int,
    @ColorRes val enabledBackground: Int
) {
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
            var icon: Drawable?
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
                    icon.mutate()
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
        Pair(R.color.thermo_heat_foreground, R.color.control_enabled_thermo_heat_background),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_COOL) to
        Pair(R.color.thermo_cool_foreground, R.color.control_enabled_thermo_cool_background),
    DeviceTypes.TYPE_LIGHT
        to Pair(R.color.light_foreground, R.color.control_enabled_light_background)
).withDefault {
        Pair(R.color.control_foreground, R.color.control_enabled_default_background)
}

private val deviceIconMap = mapOf<Int, IconState>(
    THERMOSTAT_RANGE to IconState(
        R.drawable.ic_device_thermostat_off,
        R.drawable.ic_device_thermostat_on
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_OFF) to IconState(
        R.drawable.ic_device_thermostat_off,
        R.drawable.ic_device_thermostat_on
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_HEAT) to IconState(
        R.drawable.ic_device_thermostat_off,
        R.drawable.ic_device_thermostat_on
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_COOL) to IconState(
        R.drawable.ic_device_thermostat_off,
        R.drawable.ic_device_thermostat_on
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_HEAT_COOL) to IconState(
        R.drawable.ic_device_thermostat_off,
        R.drawable.ic_device_thermostat_on
    ),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_ECO) to IconState(
        R.drawable.ic_device_thermostat_off,
        R.drawable.ic_device_thermostat_on
    ),
    DeviceTypes.TYPE_THERMOSTAT to IconState(
        R.drawable.ic_device_thermostat_off,
        R.drawable.ic_device_thermostat_on
    ),
    DeviceTypes.TYPE_LIGHT to IconState(
        R.drawable.ic_device_light_off,
        R.drawable.ic_device_light_on
    ),
    DeviceTypes.TYPE_CAMERA to IconState(
        R.drawable.ic_device_camera_off,
        R.drawable.ic_device_camera_on
    ),
    DeviceTypes.TYPE_LOCK to IconState(
        R.drawable.ic_device_lock_off,
        R.drawable.ic_device_lock_on
    ),
    DeviceTypes.TYPE_SWITCH to IconState(
        R.drawable.ic_device_switch_off,
        R.drawable.ic_device_switch_on
    ),
    DeviceTypes.TYPE_OUTLET to IconState(
        R.drawable.ic_device_outlet_off,
        R.drawable.ic_device_outlet_on
    ),
    DeviceTypes.TYPE_VACUUM to IconState(
        R.drawable.ic_device_vacuum_off,
        R.drawable.ic_device_vacuum_on
    ),
    DeviceTypes.TYPE_MOP to IconState(
        R.drawable.ic_device_mop_off,
        R.drawable.ic_device_mop_on
    ),
    DeviceTypes.TYPE_AIR_FRESHENER to IconState(
        R.drawable.ic_device_air_freshener_off,
        R.drawable.ic_device_air_freshener_on
    ),
    DeviceTypes.TYPE_AIR_PURIFIER to IconState(
        R.drawable.ic_device_air_purifier_off,
        R.drawable.ic_device_air_purifier_on
    ),
    DeviceTypes.TYPE_FAN to IconState(
        R.drawable.ic_device_fan_off,
        R.drawable.ic_device_fan_on
    ),
    DeviceTypes.TYPE_HOOD to IconState(
        R.drawable.ic_device_hood_off,
        R.drawable.ic_device_hood_on
    ),
    DeviceTypes.TYPE_KETTLE to IconState(
        R.drawable.ic_device_kettle_off,
        R.drawable.ic_device_kettle_on
    ),
    DeviceTypes.TYPE_MICROWAVE to IconState(
        R.drawable.ic_device_microwave_off,
        R.drawable.ic_device_microwave_on
    ),
    DeviceTypes.TYPE_REMOTE_CONTROL to IconState(
        R.drawable.ic_device_remote_control_off,
        R.drawable.ic_device_remote_control_on
    ),
    DeviceTypes.TYPE_SET_TOP to IconState(
        R.drawable.ic_device_set_top_off,
        R.drawable.ic_device_set_top_on
    ),
    DeviceTypes.TYPE_STYLER to IconState(
        R.drawable.ic_device_styler_off,
        R.drawable.ic_device_styler_on
    ),
    DeviceTypes.TYPE_TV to IconState(
        R.drawable.ic_device_tv_off,
        R.drawable.ic_device_tv_on
    ),
    DeviceTypes.TYPE_WATER_HEATER to IconState(
        R.drawable.ic_device_water_heater_off,
        R.drawable.ic_device_water_heater_on
    ),
    DeviceTypes.TYPE_DISHWASHER to IconState(
        R.drawable.ic_device_dishwasher_off,
        R.drawable.ic_device_dishwasher_on
    ),
    DeviceTypes.TYPE_MULTICOOKER to IconState(
        R.drawable.ic_device_multicooker_off,
        R.drawable.ic_device_multicooker_on
    ),
    DeviceTypes.TYPE_SPRINKLER to IconState(
        R.drawable.ic_device_sprinkler_off,
        R.drawable.ic_device_sprinkler_on
    ),
    DeviceTypes.TYPE_WASHER to IconState(
        R.drawable.ic_device_washer_off,
        R.drawable.ic_device_washer_on
    ),
    DeviceTypes.TYPE_BLINDS to IconState(
        R.drawable.ic_device_blinds_off,
        R.drawable.ic_device_blinds_on
    ),
    DeviceTypes.TYPE_DRAWER to IconState(
        R.drawable.ic_device_drawer_off,
        R.drawable.ic_device_drawer_on
    ),
    DeviceTypes.TYPE_GARAGE to IconState(
        R.drawable.ic_device_garage_off,
        R.drawable.ic_device_garage_on
    ),
    DeviceTypes.TYPE_GATE to IconState(
        R.drawable.ic_device_gate_off,
        R.drawable.ic_device_gate_on
    ),
    DeviceTypes.TYPE_PERGOLA to IconState(
        R.drawable.ic_device_pergola_off,
        R.drawable.ic_device_pergola_on
    ),
    DeviceTypes.TYPE_WINDOW to IconState(
        R.drawable.ic_device_window_off,
        R.drawable.ic_device_window_on
    ),
    DeviceTypes.TYPE_VALVE to IconState(
        R.drawable.ic_device_valve_off,
        R.drawable.ic_device_valve_on
    ),
    DeviceTypes.TYPE_SECURITY_SYSTEM to IconState(
        R.drawable.ic_device_security_system_off,
        R.drawable.ic_device_security_system_on
    ),
    DeviceTypes.TYPE_REFRIGERATOR to IconState(
        R.drawable.ic_device_refrigerator_off,
        R.drawable.ic_device_refrigerator_on
    ),
    DeviceTypes.TYPE_DOORBELL to IconState(
        R.drawable.ic_device_doorbell_off,
        R.drawable.ic_device_doorbell_on
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
