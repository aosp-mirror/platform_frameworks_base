/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.content.res

import android.platform.test.annotations.Presubmit
import android.platform.test.ravenwood.RavenwoodRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Presubmit
@RunWith(AndroidJUnit4::class)
class FontScaleConverterTest {

    @get:Rule
    val ravenwoodRule: RavenwoodRule = RavenwoodRule.Builder().build()

    @Test
    fun straightInterpolation() {
        val table = createTable(8f to 8f, 10f to 10f, 20f to 20f)
        verifyConversionBothWays(table, 1f, 1F)
        verifyConversionBothWays(table, 8f, 8F)
        verifyConversionBothWays(table, 10f, 10F)
        verifyConversionBothWays(table, 30f, 30F)
        verifyConversionBothWays(table, 20f, 20F)
        verifyConversionBothWays(table, 5f, 5F)
        verifyConversionBothWays(table, 0f, 0F)
    }

    @Test
    fun interpolate200Percent() {
        val table = createTable(8f to 16f, 10f to 20f, 30f to 60f)
        verifyConversionBothWays(table, 2f, 1F)
        verifyConversionBothWays(table, 16f, 8F)
        verifyConversionBothWays(table, 20f, 10F)
        verifyConversionBothWays(table, 60f, 30F)
        verifyConversionBothWays(table, 40f, 20F)
        verifyConversionBothWays(table, 10f, 5F)
        verifyConversionBothWays(table, 0f, 0F)
    }

    @Test
    fun interpolate150Percent() {
        val table = createTable(2f to 3f, 10f to 15f, 20f to 30f, 100f to 150f)
        verifyConversionBothWays(table, 3f, 2F)
        verifyConversionBothWays(table, 1.5f, 1F)
        verifyConversionBothWays(table, 12f, 8F)
        verifyConversionBothWays(table, 15f, 10F)
        verifyConversionBothWays(table, 30f, 20F)
        verifyConversionBothWays(table, 75f, 50F)
        verifyConversionBothWays(table, 7.5f, 5F)
        verifyConversionBothWays(table, 0f, 0F)
    }

    @Test
    fun pastEndsUsesLastScalingFactor() {
        val table = createTable(8f to 16f, 10f to 20f, 30f to 60f)
        verifyConversionBothWays(table, 200f, 100F)
        verifyConversionBothWays(table, 62f, 31F)
        verifyConversionBothWays(table, 2000f, 1000F)
        verifyConversionBothWays(table, 4000f, 2000F)
        verifyConversionBothWays(table, 20000f, 10000F)
    }

    @Test
    fun negativeSpIsNegativeDp() {
        val table = createTable(8f to 16f, 10f to 20f, 30f to 60f)
        verifyConversionBothWays(table, -2f, -1F)
        verifyConversionBothWays(table, -16f, -8F)
        verifyConversionBothWays(table, -20f, -10F)
        verifyConversionBothWays(table, -60f, -30F)
        verifyConversionBothWays(table, -40f, -20F)
        verifyConversionBothWays(table, -10f, -5F)
        verifyConversionBothWays(table, 0f, -0F)
    }

    private fun createTable(vararg pairs: Pair<Float, Float>) =
        FontScaleConverterImpl(
            pairs.map { it.first }.toFloatArray(),
            pairs.map { it.second }.toFloatArray()
        )

    private fun verifyConversionBothWays(
        table: FontScaleConverterImpl,
        expectedDp: Float,
        spToConvert: Float
    ) {
        assertWithMessage("convertSpToDp")
            .that(table.convertSpToDp(spToConvert))
            .isWithin(CONVERSION_TOLERANCE)
            .of(expectedDp)

        assertWithMessage("inverse: convertDpToSp")
            .that(table.convertDpToSp(expectedDp))
            .isWithin(CONVERSION_TOLERANCE)
            .of(spToConvert)
    }

    companion object {
        private const val CONVERSION_TOLERANCE = 0.05f
    }
}
