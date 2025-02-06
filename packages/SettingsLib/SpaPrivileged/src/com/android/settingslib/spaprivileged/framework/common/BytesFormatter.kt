/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.framework.common

import android.content.Context
import android.content.res.Resources
import android.icu.text.DecimalFormat
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.text.UnicodeSet
import android.icu.text.UnicodeSetSpanner
import android.icu.util.Measure
import android.text.BidiFormatter
import android.text.format.Formatter
import android.text.format.Formatter.RoundedBytesResult
import java.math.BigDecimal

class BytesFormatter(resources: Resources) {

    enum class UseCase(val flag: Int) {
        FileSize(Formatter.FLAG_SI_UNITS),
        DataUsage(Formatter.FLAG_IEC_UNITS),
    }

    data class Result(val number: String, val units: String)

    constructor(context: Context) : this(context.resources)

    private val locale = resources.configuration.locales[0]
    private val bidiFormatter = BidiFormatter.getInstance(locale)

    fun format(bytes: Long, useCase: UseCase): String {
        val rounded = RoundedBytesResult.roundBytes(bytes, useCase.flag)
        val numberFormatter = getNumberFormatter(rounded.fractionDigits)
        val formattedString = numberFormatter.formatRoundedBytesResult(rounded)
        return if (useCase == UseCase.FileSize) {
            formattedString.bidiWrap()
        } else {
            formattedString
        }
    }

    fun formatWithUnits(bytes: Long, useCase: UseCase): Result {
        val rounded = RoundedBytesResult.roundBytes(bytes, useCase.flag)
        val numberFormatter = getNumberFormatter(rounded.fractionDigits)
        val formattedString = numberFormatter.formatRoundedBytesResult(rounded)
        val formattedNumber = numberFormatter.format(rounded.value)
        return Result(
            number = formattedNumber,
            units = formattedString.removeFirst(formattedNumber),
        )
    }

    private fun NumberFormat.formatRoundedBytesResult(rounded: RoundedBytesResult): String {
        val measureFormatter =
            MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT, this)
        return measureFormatter.format(Measure(rounded.value, rounded.units))
    }

    private fun getNumberFormatter(fractionDigits: Int) =
        NumberFormat.getInstance(locale).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
            isGroupingUsed = false
            if (this is DecimalFormat) {
                setRoundingMode(BigDecimal.ROUND_HALF_UP)
            }
        }

    /** Wraps the source string in bidi formatting characters in RTL locales. */
    private fun String.bidiWrap(): String =
        if (bidiFormatter.isRtlContext) {
            bidiFormatter.unicodeWrap(this)
        } else {
            this
        }

    private companion object {
        fun String.removeFirst(removed: String): String =
            SPACES_AND_CONTROLS.trim(replaceFirst(removed, "")).toString()

        val SPACES_AND_CONTROLS = UnicodeSetSpanner(UnicodeSet("[[:Zs:][:Cf:]]").freeze())
    }
}
