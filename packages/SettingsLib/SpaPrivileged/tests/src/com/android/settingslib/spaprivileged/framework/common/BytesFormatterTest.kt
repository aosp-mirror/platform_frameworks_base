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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BytesFormatterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val formatter = BytesFormatter(context)

    @Test
    fun `Zero bytes`() {
        // Given a byte value of 0, the formatted output should be "0 byte" for both FileSize
        // and DataUsage UseCases. This verifies special handling of zero values.

        val fileSizeResult = formatter.format(0, BytesFormatter.UseCase.FileSize)
        assertThat(fileSizeResult).isEqualTo("0 byte")

        val dataUsageResult = formatter.format(0, BytesFormatter.UseCase.DataUsage)
        assertThat(dataUsageResult).isEqualTo("0 byte")
    }

    @Test
    fun `Positive bytes`() {
        // Given a positive byte value (e.g., 1000), the formatted output should be correctly
        // displayed with appropriate units (e.g., '1.00 kB') for both UseCases.

        val fileSizeResult = formatter.format(1000, BytesFormatter.UseCase.FileSize)
        assertThat(fileSizeResult).isEqualTo("1.00 kB")

        val dataUsageResult = formatter.format(1024, BytesFormatter.UseCase.DataUsage)
        assertThat(dataUsageResult).isEqualTo("1.00 kB")
    }

    @Test
    fun `Large bytes`() {
        // Given a very large byte value (e.g., Long.MAX_VALUE), the formatted output should be
        // correctly displayed with the largest unit (e.g., 'PB') for both UseCases.

        val fileSizeResult = formatter.format(Long.MAX_VALUE, BytesFormatter.UseCase.FileSize)
        assertThat(fileSizeResult).isEqualTo("9223 PB")

        val dataUsageResult = formatter.format(Long.MAX_VALUE, BytesFormatter.UseCase.DataUsage)
        assertThat(dataUsageResult).isEqualTo("8192 PB")
    }

    @Test
    fun `Bytes requiring rounding`() {
        // Given byte values that require rounding (e.g., 1512), the formatted output should be
        // rounded to the appropriate number of decimal places (e.g., '1.51 kB').

        val fileSizeResult = formatter.format(1512, BytesFormatter.UseCase.FileSize)
        assertThat(fileSizeResult).isEqualTo("1.51 kB")

        val dataUsageResult = formatter.format(1512, BytesFormatter.UseCase.DataUsage)
        assertThat(dataUsageResult).isEqualTo("1.48 kB")
    }

    @Test
    fun `FileSize UseCase`() {
        // When the UseCase is FileSize, the correct units (byte, KB, kB, GB, TB, PB) should
        // be used.
        val values =
            listOf(
                1L,
                1024L,
                1024L * 1024L,
                1024L * 1024L * 1024L,
                1024L * 1024L * 1024L * 1024L,
                1024L * 1024L * 1024L * 1024L * 1024L,
                1024L * 1024L * 1024L * 1024L * 1024L * 1024L,
            )
        val expectedUnits = listOf("byte", "kB", "MB", "GB", "TB", "PB", "PB")

        values.zip(expectedUnits).forEach { (value, expectedUnit) ->
            val result = formatter.format(value, BytesFormatter.UseCase.FileSize)
            assertThat(result).contains(expectedUnit)
        }
    }

    @Test
    fun `DataUsage UseCase`() {
        // When the UseCase is DataUsage, the correct units (byte, kB, MB, GB, TB, PB) should
        // be used.
        val values =
            listOf(
                1L,
                1024L,
                1024L * 1024L,
                1024L * 1024L * 1024L,
                1024L * 1024L * 1024L * 1024L,
                1024L * 1024L * 1024L * 1024L * 1024L,
                1024L * 1024L * 1024L * 1024L * 1024L * 1024L,
            )
        val expectedUnits = listOf("byte", "kB", "MB", "GB", "TB", "PB", "PB")

        values.zip(expectedUnits).forEach { (value, expectedUnit) ->
            val result = formatter.format(value, BytesFormatter.UseCase.DataUsage)
            assertThat(result).contains(expectedUnit)
        }
    }

    @Test
    fun `Fraction digits`() {
        // The number of fraction digits in the output should be correctly determined based on
        // the rounded byte value.

        assertThat(formatter.format(1500, BytesFormatter.UseCase.FileSize)).isEqualTo("1.50 kB")
        assertThat(formatter.format(1050, BytesFormatter.UseCase.FileSize)).isEqualTo("1.05 kB")
        assertThat(formatter.format(999, BytesFormatter.UseCase.FileSize)).isEqualTo("1.00 kB")
    }

    @Test
    fun `Rounding mode`() {
        // The rounding mode used for formatting should be ROUND_HALF_UP.

        val result = formatter.format(1006, BytesFormatter.UseCase.FileSize)

        assertThat(result).isEqualTo("1.01 kB") // Ensure rounding mode is effective
    }

    @Test
    fun `Grouping separator`() {
        // Grouping separators should not be used in the formatted output.

        val result = formatter.format(Long.MAX_VALUE, BytesFormatter.UseCase.FileSize)

        assertThat(result).isEqualTo("9223 PB")
    }

    @Test
    fun `Format with units`() {
        // Verify that the `formatWithUnits` method correctly formats the given bytes with the
        // specified units.

        val resultByte = formatter.formatWithUnits(0, BytesFormatter.UseCase.FileSize)
        assertThat(resultByte).isEqualTo(BytesFormatter.Result("0", "byte"))

        val resultKb = formatter.formatWithUnits(1000, BytesFormatter.UseCase.FileSize)
        assertThat(resultKb).isEqualTo(BytesFormatter.Result("1.00", "kB"))

        val resultMb = formatter.formatWithUnits(479_999_999, BytesFormatter.UseCase.FileSize)
        assertThat(resultMb).isEqualTo(BytesFormatter.Result("480", "MB"))

        val resultGb = formatter.formatWithUnits(20_100_000_000, BytesFormatter.UseCase.FileSize)
        assertThat(resultGb).isEqualTo(BytesFormatter.Result("20.10", "GB"))

        val resultTb =
            formatter.formatWithUnits(300_100_000_000_000, BytesFormatter.UseCase.FileSize)
        assertThat(resultTb).isEqualTo(BytesFormatter.Result("300", "TB"))

        val resultPb =
            formatter.formatWithUnits(1000_000_000_000_000, BytesFormatter.UseCase.FileSize)
        assertThat(resultPb).isEqualTo(BytesFormatter.Result("1.00", "PB"))
    }
}
