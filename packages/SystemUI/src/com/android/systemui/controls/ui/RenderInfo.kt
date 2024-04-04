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

data class RenderInfo(
    val icon: Drawable,
    val foreground: Int,
    @ColorRes val enabledBackground: Int
) {
    companion object {
        const val APP_ICON_ID = -1
        const val ERROR_ICON = -1000
        private val iconMap = SparseArray<Drawable>()
        private val appIconMap = ArrayMap<ComponentName, Drawable>()

        @MainThread
        fun lookup(
            context: Context,
            componentName: ComponentName,
            deviceType: Int,
            offset: Int = 0
        ): RenderInfo {
            val key = if (offset > 0) {
                deviceType * BUCKET_SIZE + offset
            } else deviceType

            val (fg, bg) = deviceColorMap.getValue(key)
            val resourceId = deviceIconMap.getValue(key)
            var icon: Drawable?
            if (resourceId == APP_ICON_ID) {
                icon = appIconMap.get(componentName)
                if (icon == null) {
                    icon = context.resources
                        .getDrawable(R.drawable.ic_device_unknown_on, null)
                    appIconMap.put(componentName, icon)
                }
            } else {
                icon = iconMap.get(resourceId)
                if (icon == null) {
                    icon = context.resources.getDrawable(resourceId, null)
                    iconMap.put(resourceId, icon)
                }
            }
            return RenderInfo(
                checkNotNull(icon?.constantState).newDrawable(context.resources), fg, bg)
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
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_OFF) to
        Pair(R.color.control_default_foreground, R.color.control_default_background),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_HEAT) to
        Pair(R.color.thermo_heat_foreground, R.color.control_enabled_thermo_heat_background),
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_COOL) to
        Pair(R.color.thermo_cool_foreground, R.color.control_enabled_thermo_cool_background),
    DeviceTypes.TYPE_LIGHT
        to Pair(R.color.light_foreground, R.color.control_enabled_light_background),
    DeviceTypes.TYPE_CAMERA
        to Pair(R.color.camera_foreground, R.color.control_enabled_default_background)
).withDefault {
        Pair(R.color.control_foreground, R.color.control_enabled_default_background)
}

private val deviceIconMap = mapOf<Int, Int>(
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_OFF) to
        R.drawable.ic_device_thermostat_off,
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_HEAT) to
        R.drawable.ic_device_thermostat,
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_COOL) to
        R.drawable.ic_device_thermostat,
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_HEAT_COOL) to
        R.drawable.ic_device_thermostat,
    (THERMOSTAT_RANGE + TemperatureControlTemplate.MODE_ECO) to
        R.drawable.ic_device_thermostat_off,
    DeviceTypes.TYPE_THERMOSTAT to R.drawable.ic_device_thermostat,
    DeviceTypes.TYPE_LIGHT to R.drawable.ic_device_light,
    DeviceTypes.TYPE_CAMERA to R.drawable.ic_device_camera,
    DeviceTypes.TYPE_LOCK to R.drawable.ic_device_lock,
    DeviceTypes.TYPE_SWITCH to R.drawable.ic_device_switch,
    DeviceTypes.TYPE_OUTLET to R.drawable.ic_device_outlet,
    DeviceTypes.TYPE_VACUUM to R.drawable.ic_device_vacuum,
    DeviceTypes.TYPE_MOP to R.drawable.ic_device_mop,
    DeviceTypes.TYPE_AIR_FRESHENER to R.drawable.ic_device_air_freshener,
    DeviceTypes.TYPE_AIR_PURIFIER to R.drawable.ic_device_air_purifier,
    DeviceTypes.TYPE_FAN to R.drawable.ic_device_fan,
    DeviceTypes.TYPE_HOOD to R.drawable.ic_device_hood,
    DeviceTypes.TYPE_KETTLE to R.drawable.ic_device_kettle,
    DeviceTypes.TYPE_MICROWAVE to R.drawable.ic_device_microwave,
    DeviceTypes.TYPE_REMOTE_CONTROL to R.drawable.ic_device_remote_control,
    DeviceTypes.TYPE_SET_TOP to R.drawable.ic_device_set_top,
    DeviceTypes.TYPE_STYLER to R.drawable.ic_device_styler,
    DeviceTypes.TYPE_TV to R.drawable.ic_device_tv,
    DeviceTypes.TYPE_WATER_HEATER to R.drawable.ic_device_water_heater,
    DeviceTypes.TYPE_DISHWASHER to R.drawable.ic_device_dishwasher,
    DeviceTypes.TYPE_MULTICOOKER to R.drawable.ic_device_multicooker,
    DeviceTypes.TYPE_SPRINKLER to R.drawable.ic_device_sprinkler,
    DeviceTypes.TYPE_WASHER to R.drawable.ic_device_washer,
    DeviceTypes.TYPE_BLINDS to R.drawable.ic_device_blinds,
    DeviceTypes.TYPE_DRAWER to R.drawable.ic_device_drawer,
    DeviceTypes.TYPE_GARAGE to R.drawable.ic_device_garage,
    DeviceTypes.TYPE_GATE to R.drawable.ic_device_gate,
    DeviceTypes.TYPE_PERGOLA to R.drawable.ic_device_pergola,
    DeviceTypes.TYPE_WINDOW to R.drawable.ic_device_window,
    DeviceTypes.TYPE_VALVE to R.drawable.ic_device_valve,
    DeviceTypes.TYPE_SECURITY_SYSTEM to R.drawable.ic_device_security_system,
    DeviceTypes.TYPE_REFRIGERATOR to R.drawable.ic_device_refrigerator,
    DeviceTypes.TYPE_DOORBELL to R.drawable.ic_device_doorbell,
    DeviceTypes.TYPE_ROUTINE to RenderInfo.APP_ICON_ID,
    DeviceTypes.TYPE_AC_HEATER to R.drawable.ic_device_thermostat,
    DeviceTypes.TYPE_AC_UNIT to R.drawable.ic_device_thermostat,
    DeviceTypes.TYPE_COFFEE_MAKER to R.drawable.ic_device_kettle,
    DeviceTypes.TYPE_DEHUMIDIFIER to R.drawable.ic_device_air_freshener,
    DeviceTypes.TYPE_RADIATOR to R.drawable.ic_device_thermostat,
    DeviceTypes.TYPE_STANDMIXER to R.drawable.ic_device_cooking,
    DeviceTypes.TYPE_DISPLAY to R.drawable.ic_device_display,
    DeviceTypes.TYPE_DRYER to R.drawable.ic_device_washer,
    DeviceTypes.TYPE_MOWER to R.drawable.ic_device_outdoor_garden,
    DeviceTypes.TYPE_SHOWER to R.drawable.ic_device_water,
    DeviceTypes.TYPE_AWNING to R.drawable.ic_device_pergola,
    DeviceTypes.TYPE_CLOSET to R.drawable.ic_device_drawer,
    DeviceTypes.TYPE_CURTAIN to R.drawable.ic_device_blinds,
    DeviceTypes.TYPE_DOOR to R.drawable.ic_device_door,
    DeviceTypes.TYPE_SHUTTER to R.drawable.ic_device_window,
    DeviceTypes.TYPE_HEATER to R.drawable.ic_device_thermostat,
    RenderInfo.ERROR_ICON to R.drawable.ic_error_outline
).withDefault {
    R.drawable.ic_device_unknown
}
