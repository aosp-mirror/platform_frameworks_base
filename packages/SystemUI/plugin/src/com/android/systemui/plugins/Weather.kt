package com.android.systemui.statusbar

import android.os.Bundle

class Weather(val conditions: WeatherStateIcon, val temperature: Int, val isCelsius: Boolean) {
    companion object {
        private const val TAG = "Weather"
        private const val WEATHER_STATE_ICON_KEY = "weather_state_icon_extra_key"
        private const val TEMPERATURE_VALUE_KEY = "temperature_value_extra_key"
        private const val TEMPERATURE_UNIT_KEY = "temperature_unit_extra_key"
        private const val INVALID_TEMPERATURE = Int.MIN_VALUE

        fun fromBundle(extras: Bundle): Weather? {
            val icon =
                WeatherStateIcon.fromInt(
                    extras.getInt(WEATHER_STATE_ICON_KEY, WeatherStateIcon.UNKNOWN_ICON.id)
                )
            if (icon == null || icon == WeatherStateIcon.UNKNOWN_ICON) {
                return null
            }
            val temperature = extras.getInt(TEMPERATURE_VALUE_KEY, INVALID_TEMPERATURE)
            if (temperature == INVALID_TEMPERATURE) {
                return null
            }
            return Weather(icon, temperature, extras.getBoolean(TEMPERATURE_UNIT_KEY))
        }
    }

    enum class WeatherStateIcon(val id: Int) {
        UNKNOWN_ICON(0),

        // Clear, day & night.
        SUNNY(1),
        CLEAR_NIGHT(2),

        // Mostly clear, day & night.
        MOSTLY_SUNNY(3),
        MOSTLY_CLEAR_NIGHT(4),

        // Partly cloudy, day & night.
        PARTLY_CLOUDY(5),
        PARTLY_CLOUDY_NIGHT(6),

        // Mostly cloudy, day & night.
        MOSTLY_CLOUDY_DAY(7),
        MOSTLY_CLOUDY_NIGHT(8),
        CLOUDY(9),
        HAZE_FOG_DUST_SMOKE(10),
        DRIZZLE(11),
        HEAVY_RAIN(12),
        SHOWERS_RAIN(13),

        // Scattered showers, day & night.
        SCATTERED_SHOWERS_DAY(14),
        SCATTERED_SHOWERS_NIGHT(15),

        // Isolated scattered thunderstorms, day & night.
        ISOLATED_SCATTERED_TSTORMS_DAY(16),
        ISOLATED_SCATTERED_TSTORMS_NIGHT(17),
        STRONG_TSTORMS(18),
        BLIZZARD(19),
        BLOWING_SNOW(20),
        FLURRIES(21),
        HEAVY_SNOW(22),

        // Scattered snow showers, day & night.
        SCATTERED_SNOW_SHOWERS_DAY(23),
        SCATTERED_SNOW_SHOWERS_NIGHT(24),
        SNOW_SHOWERS_SNOW(25),
        MIXED_RAIN_HAIL_RAIN_SLEET(26),
        SLEET_HAIL(27),
        TORNADO(28),
        TROPICAL_STORM_HURRICANE(29),
        WINDY_BREEZY(30),
        WINTRY_MIX_RAIN_SNOW(31);

        companion object {
            fun fromInt(value: Int) = values().firstOrNull { it.id == value }
        }
    }

    override fun toString(): String {
        return "$conditions $temperature${if (isCelsius) "C" else "F"}"
    }
}
