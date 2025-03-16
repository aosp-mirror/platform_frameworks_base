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

package com.android.systemui.shared.clocks

import android.icu.text.DateFormat
import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone as IcuTimeZone
import android.icu.util.ULocale
import androidx.annotation.VisibleForTesting
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

open class TimespecHandler(
    val cal: Calendar,
) {
    var timeZone: TimeZone
        get() = cal.timeZone
        set(value) {
            cal.timeZone = value
            onTimeZoneChanged()
        }

    @VisibleForTesting var fakeTimeMills: Long? = null

    fun updateTime() {
        var timeMs = fakeTimeMills ?: System.currentTimeMillis()
        cal.timeInMillis = (timeMs * TIME_TRAVEL_SCALE).toLong()
    }

    protected open fun onTimeZoneChanged() {}

    companion object {
        // Modifying this will cause the clock to run faster or slower. This is a useful way of
        // manually checking that clocks are correctly animating through time.
        private const val TIME_TRAVEL_SCALE = 1.0
    }
}

class DigitalTimespecHandler(
    val timespec: DigitalTimespec,
    private val timeFormat: String,
    cal: Calendar = Calendar.getInstance(),
) : TimespecHandler(cal) {
    var is24Hr = false
        set(value) {
            field = value
            applyPattern()
        }

    private var dateFormat = updateSimpleDateFormat(Locale.getDefault())
    private var contentDescriptionFormat = getContentDescriptionFormat(Locale.getDefault())

    init {
        applyPattern()
    }

    override fun onTimeZoneChanged() {
        dateFormat.timeZone = IcuTimeZone.getTimeZone(cal.timeZone.id)
        contentDescriptionFormat?.timeZone = IcuTimeZone.getTimeZone(cal.timeZone.id)
        applyPattern()
    }

    fun updateLocale(locale: Locale) {
        dateFormat = updateSimpleDateFormat(locale)
        contentDescriptionFormat = getContentDescriptionFormat(locale)
        onTimeZoneChanged()
    }

    private fun updateSimpleDateFormat(locale: Locale): DateFormat {
        if (
            locale.language.equals(Locale.ENGLISH.language) ||
                timespec != DigitalTimespec.DATE_FORMAT
        ) {
            // force date format in English, and time format to use format defined in json
            return SimpleDateFormat(timeFormat, timeFormat, ULocale.forLocale(locale))
        } else {
            return SimpleDateFormat.getInstanceForSkeleton(timeFormat, locale)
        }
    }

    private fun getContentDescriptionFormat(locale: Locale): DateFormat? {
        return when (timespec) {
            DigitalTimespec.TIME_FULL_FORMAT ->
                SimpleDateFormat.getInstanceForSkeleton("hh:mm", locale)
            DigitalTimespec.DATE_FORMAT ->
                SimpleDateFormat.getInstanceForSkeleton("EEEE MMMM d", locale)
            else -> {
                null
            }
        }
    }

    private fun applyPattern() {
        val timeFormat24Hour = timeFormat.replace("hh", "h").replace("h", "HH")
        val format = if (is24Hr) timeFormat24Hour else timeFormat
        if (timespec != DigitalTimespec.DATE_FORMAT) {
            (dateFormat as SimpleDateFormat).applyPattern(format)
            (contentDescriptionFormat as? SimpleDateFormat)?.applyPattern(
                if (is24Hr) CONTENT_DESCRIPTION_TIME_FORMAT_24_HOUR
                else CONTENT_DESCRIPTION_TIME_FORMAT_12_HOUR
            )
        }
    }

    private fun getSingleDigit(): String {
        val isFirstDigit = timespec == DigitalTimespec.FIRST_DIGIT
        val text = dateFormat.format(cal.time).toString()
        return text.substring(
            if (isFirstDigit) 0 else text.length - 1,
            if (isFirstDigit) text.length - 1 else text.length
        )
    }

    fun getDigitString(): String {
        return when (timespec) {
            DigitalTimespec.FIRST_DIGIT,
            DigitalTimespec.SECOND_DIGIT -> getSingleDigit()
            DigitalTimespec.DIGIT_PAIR -> {
                dateFormat.format(cal.time).toString()
            }
            DigitalTimespec.TIME_FULL_FORMAT -> {
                dateFormat.format(cal.time).toString()
            }
            DigitalTimespec.DATE_FORMAT -> {
                dateFormat.format(cal.time).toString().uppercase()
            }
        }
    }

    fun getContentDescription(): String? {
        return when (timespec) {
            DigitalTimespec.TIME_FULL_FORMAT,
            DigitalTimespec.DATE_FORMAT -> {
                contentDescriptionFormat?.format(cal.time).toString()
            }
            else -> {
                return null
            }
        }
    }

    companion object {
        const val CONTENT_DESCRIPTION_TIME_FORMAT_12_HOUR = "hh:mm"
        const val CONTENT_DESCRIPTION_TIME_FORMAT_24_HOUR = "HH:mm"
    }
}
