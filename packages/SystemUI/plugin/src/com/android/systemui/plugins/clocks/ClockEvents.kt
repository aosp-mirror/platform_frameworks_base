/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.plugins.clocks

import com.android.systemui.plugins.annotations.ProtectedInterface
import com.android.systemui.plugins.annotations.ProtectedReturn
import java.util.Locale
import java.util.TimeZone

/** Events that should call when various rendering parameters change */
@ProtectedInterface
interface ClockEvents {
    @get:ProtectedReturn("return false;")
    /** Set to enable or disable swipe interaction */
    var isReactiveTouchInteractionEnabled: Boolean // TODO(b/364664388): Remove/Rename

    /** Call whenever timezone changes */
    fun onTimeZoneChanged(timeZone: TimeZone)

    /** Call whenever the text time format changes (12hr vs 24hr) */
    fun onTimeFormatChanged(is24Hr: Boolean)

    /** Call whenever the locale changes */
    fun onLocaleChanged(locale: Locale)

    /** Call whenever the weather data should update */
    fun onWeatherDataChanged(data: WeatherData)

    /** Call with alarm information */
    fun onAlarmDataChanged(data: AlarmData)

    /** Call with zen/dnd information */
    fun onZenDataChanged(data: ZenData)

    /** Update reactive axes for this clock */
    fun onFontAxesChanged(axes: List<ClockFontAxisSetting>)
}
