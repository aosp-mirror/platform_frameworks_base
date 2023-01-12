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

package android.util

import android.content.res.FontScaleConverterFactory
import android.platform.test.annotations.Presubmit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Presubmit
@RunWith(AndroidJUnit4::class)
class TypedValueTest {
    @LargeTest
    @Test
    fun testFloatToComplex() {
        fun assertRoundTripEquals(value: Float, expectedRadix: Int? = null) {
            val complex = TypedValue.floatToComplex(value)
            // Ensure values are accurate within .5% of the original value and within .5
            val delta = min(abs(value) / 512f, .5f)
            assertEquals(value, TypedValue.complexToFloat(complex), delta)
            // If expectedRadix is provided, validate it
            if (expectedRadix != null) {
                val actualRadix = ((complex shr TypedValue.COMPLEX_RADIX_SHIFT)
                        and TypedValue.COMPLEX_RADIX_MASK)
                assertEquals("Incorrect radix for $value:", expectedRadix, actualRadix)
            }
        }

        assertRoundTripEquals(0f, TypedValue.COMPLEX_RADIX_23p0)

        assertRoundTripEquals(0.5f, TypedValue.COMPLEX_RADIX_0p23)
        assertRoundTripEquals(0.05f, TypedValue.COMPLEX_RADIX_0p23)
        assertRoundTripEquals(0.005f, TypedValue.COMPLEX_RADIX_0p23)
        assertRoundTripEquals(0.0005f, TypedValue.COMPLEX_RADIX_0p23)
        assertRoundTripEquals(0.00005f, TypedValue.COMPLEX_RADIX_0p23)

        assertRoundTripEquals(1.5f, TypedValue.COMPLEX_RADIX_8p15)
        assertRoundTripEquals(10.5f, TypedValue.COMPLEX_RADIX_8p15)
        assertRoundTripEquals(100.5f, TypedValue.COMPLEX_RADIX_8p15)
        assertRoundTripEquals(255.5f, TypedValue.COMPLEX_RADIX_8p15) // 2^8 - .5

        assertRoundTripEquals(256.5f, TypedValue.COMPLEX_RADIX_16p7) // 2^8 + .5
        assertRoundTripEquals(1000.5f, TypedValue.COMPLEX_RADIX_16p7)
        assertRoundTripEquals(10000.5f, TypedValue.COMPLEX_RADIX_16p7)
        assertRoundTripEquals(65535.5f, TypedValue.COMPLEX_RADIX_16p7) // 2^16 - .5

        assertRoundTripEquals(65536.5f, TypedValue.COMPLEX_RADIX_23p0) // 2^16 + .5
        assertRoundTripEquals(100000.5f, TypedValue.COMPLEX_RADIX_23p0)
        assertRoundTripEquals(1000000.5f, TypedValue.COMPLEX_RADIX_23p0)
        assertRoundTripEquals(8388607.2f, TypedValue.COMPLEX_RADIX_23p0) // 2^23 -.8

        assertRoundTripEquals(-0.5f, TypedValue.COMPLEX_RADIX_0p23)
        assertRoundTripEquals(-0.05f, TypedValue.COMPLEX_RADIX_0p23)
        assertRoundTripEquals(-0.005f, TypedValue.COMPLEX_RADIX_0p23)
        assertRoundTripEquals(-0.0005f, TypedValue.COMPLEX_RADIX_0p23)
        assertRoundTripEquals(-0.00005f, TypedValue.COMPLEX_RADIX_0p23)

        assertRoundTripEquals(-1.5f, TypedValue.COMPLEX_RADIX_8p15)
        assertRoundTripEquals(-10.5f, TypedValue.COMPLEX_RADIX_8p15)
        assertRoundTripEquals(-100.5f, TypedValue.COMPLEX_RADIX_8p15)
        assertRoundTripEquals(-255.5f, TypedValue.COMPLEX_RADIX_8p15) // -2^8 + .5

        // NOTE: -256.5f fits in COMPLEX_RADIX_8p15 but is stored with COMPLEX_RADIX_16p7 for
        // simplicity of the algorithm.  However, it's better not to enforce that with a test.
        assertRoundTripEquals(-257.5f, TypedValue.COMPLEX_RADIX_16p7) // -2^8 - 1.5
        assertRoundTripEquals(-1000.5f, TypedValue.COMPLEX_RADIX_16p7)
        assertRoundTripEquals(-10000.5f, TypedValue.COMPLEX_RADIX_16p7)
        assertRoundTripEquals(-65535.5f, TypedValue.COMPLEX_RADIX_16p7) // -2^16 + .5

        // NOTE: -65536.5f fits in COMPLEX_RADIX_16p7 but is stored with COMPLEX_RADIX_23p0 for
        // simplicity of the algorithm.  However, it's better not to enforce that with a test.
        assertRoundTripEquals(-65537.5f, TypedValue.COMPLEX_RADIX_23p0) // -2^16 - 1.5
        assertRoundTripEquals(-100000.5f, TypedValue.COMPLEX_RADIX_23p0)
        assertRoundTripEquals(-1000000.5f, TypedValue.COMPLEX_RADIX_23p0)
        assertRoundTripEquals(-8388607.5f, TypedValue.COMPLEX_RADIX_23p0) // 2^23 -.5

        // Test for every integer value in the range...
        for (i: Int in -(1 shl 23) until (1 shl 23)) {
            // ... that true integers are stored as the precise integer
            assertRoundTripEquals(i.toFloat(), TypedValue.COMPLEX_RADIX_23p0)
            // ... that values round up when just below an integer
            assertRoundTripEquals(i - .1f)
            // ... that values round down when just above an integer
            assertRoundTripEquals(i + .1f)
        }
    }

    @SmallTest
    @Test(expected = IllegalArgumentException::class)
    fun testFloatToComplex_failsIfValueTooLarge() {
        TypedValue.floatToComplex(8388607.5f) // 2^23 - .5
    }

    @SmallTest
    @Test(expected = IllegalArgumentException::class)
    fun testFloatToComplex_failsIfValueTooSmall() {
        TypedValue.floatToComplex(8388608.5f) // -2^23 - .5
    }

    @LargeTest
    @Test
    fun testIntToComplex() {
        // Validates every single valid value
        for (value: Int in -(1 shl 23) until (1 shl 23)) {
            assertEquals(value.toFloat(), TypedValue.complexToFloat(TypedValue.intToComplex(value)))
        }
    }

    @SmallTest
    @Test(expected = IllegalArgumentException::class)
    fun testIntToComplex_failsIfValueTooLarge() {
        TypedValue.intToComplex(0x800000)
    }

    @SmallTest
    @Test(expected = IllegalArgumentException::class)
    fun testIntToComplex_failsIfValueTooSmall() {
        TypedValue.intToComplex(-0x800001)
    }

    @SmallTest
    @Test
    fun testCreateComplexDimension_appliesUnits() {
        val metrics: DisplayMetrics = mock(DisplayMetrics::class.java)
        metrics.density = 3.25f

        val height = 52 * metrics.density
        val widthFloat = height * 16 / 9
        val widthDimen = TypedValue.createComplexDimension(
                widthFloat / metrics.density,
                TypedValue.COMPLEX_UNIT_DIP
        )
        val widthPx = TypedValue.complexToDimensionPixelSize(widthDimen, metrics)
        assertEquals(widthFloat.roundToInt(), widthPx)
    }

    @SmallTest
    @Test
    fun testNonLinearFontScaling_nullLookupFallsBackToScaledDensity() {
        val metrics: DisplayMetrics = mock(DisplayMetrics::class.java)
        val fontScale = 2f
        metrics.density = 1f
        metrics.xdpi = 2f
        metrics.scaledDensity = fontScale * metrics.density
        metrics.fontScaleConverter = null

        assertThat(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, metrics))
                .isEqualTo(20f)
        assertThat(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 50f, metrics))
                .isEqualTo(100f)
        assertThat(TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, 20f, metrics))
                .isEqualTo(10f)
        assertThat(TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, 100f, metrics))
                .isEqualTo(50f)
    }

    @LargeTest
    @Test
    fun testNonLinearFontScalingIsNull_deriveDimensionInversesApplyDimension() {
        val metrics: DisplayMetrics = mock(DisplayMetrics::class.java)
        val fontScale = 2f
        metrics.density = 1f
        metrics.xdpi = 2f
        metrics.scaledDensity = fontScale * metrics.density
        metrics.fontScaleConverter = null

        verifyRoundTripsForEachUnitType(metrics)
    }

    @LargeTest
    @Test
    fun testNonLinearFontScalingIs2_deriveDimensionInversesApplyDimension() {
        val metrics: DisplayMetrics = mock(DisplayMetrics::class.java)
        val fontScale = 2f
        metrics.density = 1f
        metrics.xdpi = 2f
        metrics.scaledDensity = fontScale * metrics.density
        metrics.fontScaleConverter = FontScaleConverterFactory.forScale(fontScale)

        verifyRoundTripsForEachUnitType(metrics)
    }

    @Test
    fun invalidUnitThrows() {
        val metrics: DisplayMetrics = mock(DisplayMetrics::class.java)
        val fontScale = 2f
        metrics.density = 1f
        metrics.xdpi = 2f
        metrics.scaledDensity = fontScale * metrics.density

        assertThrows(IllegalArgumentException::class.java) {
            TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_MM + 1, 23f, metrics)
        }
    }

    @Test
    fun density0_deriveDoesNotCrash() {
        val metrics: DisplayMetrics = mock(DisplayMetrics::class.java)
        metrics.density = 0f
        metrics.xdpi = 0f
        metrics.scaledDensity = 0f

        listOf(
            TypedValue.COMPLEX_UNIT_DIP,
            TypedValue.COMPLEX_UNIT_SP,
            TypedValue.COMPLEX_UNIT_PT,
            TypedValue.COMPLEX_UNIT_IN,
            TypedValue.COMPLEX_UNIT_MM
        )
            .forEach { dimenType ->
                assertThat(TypedValue.deriveDimension(dimenType, 23f, metrics))
                    .isEqualTo(0)
            }
    }

    @Test
    fun scaledDensity0_deriveSpDoesNotCrash() {
        val metrics: DisplayMetrics = mock(DisplayMetrics::class.java)
        metrics.density = 1f
        metrics.xdpi = 2f
        metrics.scaledDensity = 0f

        assertThat(TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, 23f, metrics))
            .isEqualTo(0)
    }

    private fun verifyRoundTripsForEachUnitType(metrics: DisplayMetrics) {
        listOf(
            TypedValue.COMPLEX_UNIT_PX,
            TypedValue.COMPLEX_UNIT_DIP,
            TypedValue.COMPLEX_UNIT_SP,
            TypedValue.COMPLEX_UNIT_PT,
            TypedValue.COMPLEX_UNIT_IN,
            TypedValue.COMPLEX_UNIT_MM
        )
            .forEach { dimenType ->
                for (i: Int in -10000 until 10000) {
                    assertRoundTripIsEqual(i.toFloat(), dimenType, metrics)
                    assertRoundTripIsEqual(i - .1f, dimenType, metrics)
                    assertRoundTripIsEqual(i + .5f, dimenType, metrics)
                }
            }
    }

    private fun assertRoundTripIsEqual(
        dimenValueToTest: Float,
        @TypedValue.ComplexDimensionUnit dimenType: Int,
        metrics: DisplayMetrics,
    ) {
        val actualPx = TypedValue.applyDimension(dimenType, dimenValueToTest, metrics)
        val actualDimenValue = TypedValue.deriveDimension(dimenType, actualPx, metrics)
        assertThat(dimenValueToTest)
            .isWithin(0.05f)
            .of(actualDimenValue)
    }
}
