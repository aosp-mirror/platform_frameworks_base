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
import androidx.core.util.forEach
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random.Default.nextFloat

@Presubmit
@RunWith(AndroidJUnit4::class)
class FontScaleConverterFactoryTest {

    @Test
    fun scale200IsTwiceAtSmallSizes() {
        val table = FontScaleConverterFactory.forScale(2F)!!
        assertThat(table.convertSpToDp(1F)).isWithin(CONVERSION_TOLERANCE).of(2f)
        assertThat(table.convertSpToDp(8F)).isWithin(CONVERSION_TOLERANCE).of(16f)
        assertThat(table.convertSpToDp(10F)).isWithin(CONVERSION_TOLERANCE).of(20f)
        assertThat(table.convertSpToDp(5F)).isWithin(CONVERSION_TOLERANCE).of(10f)
        assertThat(table.convertSpToDp(0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
    }

    @LargeTest
    @Test
    fun missingLookupTablePastEnd_returnsLinear() {
        val table = FontScaleConverterFactory.forScale(3F)!!
        generateSequenceOfFractions(-10000f..10000f, step = 0.01f)
            .map {
                assertThat(table.convertSpToDp(it)).isWithin(CONVERSION_TOLERANCE).of(it * 3f)
            }
        assertThat(table.convertSpToDp(1F)).isWithin(CONVERSION_TOLERANCE).of(3f)
        assertThat(table.convertSpToDp(8F)).isWithin(CONVERSION_TOLERANCE).of(24f)
        assertThat(table.convertSpToDp(10F)).isWithin(CONVERSION_TOLERANCE).of(30f)
        assertThat(table.convertSpToDp(5F)).isWithin(CONVERSION_TOLERANCE).of(15f)
        assertThat(table.convertSpToDp(0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
        assertThat(table.convertSpToDp(50F)).isWithin(CONVERSION_TOLERANCE).of(150f)
        assertThat(table.convertSpToDp(100F)).isWithin(CONVERSION_TOLERANCE).of(300f)
    }

    @SmallTest
    fun missingLookupTable110_returnsInterpolated() {
        val table = FontScaleConverterFactory.forScale(1.1F)!!

        assertThat(table.convertSpToDp(1F)).isWithin(CONVERSION_TOLERANCE).of(1.1f)
        assertThat(table.convertSpToDp(8F)).isWithin(CONVERSION_TOLERANCE).of(8f * 1.1f)
        assertThat(table.convertSpToDp(10F)).isWithin(CONVERSION_TOLERANCE).of(11f)
        assertThat(table.convertSpToDp(5F)).isWithin(CONVERSION_TOLERANCE).of(5f * 1.1f)
        assertThat(table.convertSpToDp(0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
        assertThat(table.convertSpToDp(50F)).isLessThan(50f * 1.1f)
        assertThat(table.convertSpToDp(100F)).isLessThan(100f * 1.1f)
    }

    @Test
    fun missingLookupTable199_returnsInterpolated() {
        val table = FontScaleConverterFactory.forScale(1.9999F)!!
        assertThat(table.convertSpToDp(1F)).isWithin(CONVERSION_TOLERANCE).of(2f)
        assertThat(table.convertSpToDp(8F)).isWithin(CONVERSION_TOLERANCE).of(16f)
        assertThat(table.convertSpToDp(10F)).isWithin(CONVERSION_TOLERANCE).of(20f)
        assertThat(table.convertSpToDp(5F)).isWithin(CONVERSION_TOLERANCE).of(10f)
        assertThat(table.convertSpToDp(0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
    }

    @Test
    fun missingLookupTable160_returnsInterpolated() {
        val table = FontScaleConverterFactory.forScale(1.6F)!!
        assertThat(table.convertSpToDp(1F)).isWithin(CONVERSION_TOLERANCE).of(1f * 1.6F)
        assertThat(table.convertSpToDp(8F)).isWithin(CONVERSION_TOLERANCE).of(8f * 1.6F)
        assertThat(table.convertSpToDp(10F)).isWithin(CONVERSION_TOLERANCE).of(10f * 1.6F)
        assertThat(table.convertSpToDp(20F)).isLessThan(20f * 1.6F)
        assertThat(table.convertSpToDp(100F)).isLessThan(100f * 1.6F)
        assertThat(table.convertSpToDp(5F)).isWithin(CONVERSION_TOLERANCE).of(5f * 1.6F)
        assertThat(table.convertSpToDp(0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
    }

    @SmallTest
    fun missingLookupTableNegativeReturnsNull() {
        assertThat(FontScaleConverterFactory.forScale(-1F)).isNull()
    }

    @SmallTest
    fun unnecessaryFontScalesReturnsNull() {
        assertThat(FontScaleConverterFactory.forScale(0F)).isNull()
        assertThat(FontScaleConverterFactory.forScale(1F)).isNull()
        assertThat(FontScaleConverterFactory.forScale(0.85F)).isNull()
    }

    @SmallTest
    fun tablesMatchAndAreMonotonicallyIncreasing() {
        FontScaleConverterFactory.LOOKUP_TABLES.forEach { _, lookupTable ->
            assertThat(lookupTable.mToDpValues).hasLength(lookupTable.mFromSpValues.size)
            assertThat(lookupTable.mToDpValues).isNotEmpty()

            assertThat(lookupTable.mFromSpValues.asList()).isInStrictOrder()
            assertThat(lookupTable.mToDpValues.asList()).isInStrictOrder()

            assertThat(lookupTable.mFromSpValues.asList()).containsNoDuplicates()
            assertThat(lookupTable.mToDpValues.asList()).containsNoDuplicates()
        }
    }

    @SmallTest
    fun testIsNonLinearFontScalingActive() {
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(1f)).isFalse()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(0f)).isFalse()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(-1f)).isFalse()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(0.85f)).isFalse()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(1.02f)).isFalse()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(1.10f)).isFalse()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(1.15f)).isTrue()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(1.1499999f))
                .isTrue()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(1.5f)).isTrue()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(2f)).isTrue()
        assertThat(FontScaleConverterFactory.isNonLinearFontScalingActive(3f)).isTrue()
    }

    @LargeTest
    @Test
    fun allFeasibleScalesAndConversionsDoNotCrash() {
        generateSequenceOfFractions(-10f..10f, step = 0.1f)
            .fuzzFractions()
            .mapNotNull{ FontScaleConverterFactory.forScale(it) }
            .flatMap{ table ->
                generateSequenceOfFractions(-2000f..2000f, step = 0.1f)
                    .fuzzFractions()
                    .map{ Pair(table, it) }
            }
            .forEach { (table, sp) ->
                try {
                    // Truth is slow because it creates a bunch of
                    // objects. Don't use it unless we need to.
                    if (!table.convertSpToDp(sp).isFinite()) {
                        assertWithMessage("convertSpToDp(%s) on table: %s", sp, table)
                            .that(table.convertSpToDp(sp))
                            .isFinite()
                    }
                } catch (e: Exception) {
                    throw AssertionError("Exception during convertSpToDp($sp) on table: $table", e)
                }
            }
    }

    @Test
    fun testGenerateSequenceOfFractions() {
        val fractions = generateSequenceOfFractions(-1000f..1000f, step = 0.1f)
            .toList()
        fractions.forEach {
            assertThat(it).isAtLeast(-1000f)
            assertThat(it).isAtMost(1000f)
        }

        assertThat(fractions).isInStrictOrder()
        assertThat(fractions).hasSize(1000 * 2 * 10 + 1) // Don't forget the 0 in the middle!

        assertThat(fractions).contains(100f)
        assertThat(fractions).contains(500.1f)
        assertThat(fractions).contains(500.2f)
        assertThat(fractions).contains(0.2f)
        assertThat(fractions).contains(0f)
        assertThat(fractions).contains(-10f)
        assertThat(fractions).contains(-10f)
        assertThat(fractions).contains(-10.3f)

        assertThat(fractions).doesNotContain(-10.31f)
        assertThat(fractions).doesNotContain(0.35f)
        assertThat(fractions).doesNotContain(0.31f)
        assertThat(fractions).doesNotContain(-.35f)
    }

    @Test
    fun testFuzzFractions() {
        val numFuzzedFractions = 6
        val fractions = generateSequenceOfFractions(-1000f..1000f, step = 0.1f)
            .fuzzFractions()
            .toList()
        fractions.forEach {
            assertThat(it).isAtLeast(-1000f)
            assertThat(it).isLessThan(1001f)
        }

        val numGeneratedFractions = 1000 * 2 * 10 + 1 // Don't forget the 0 in the middle!
        assertThat(fractions).hasSize(numGeneratedFractions * numFuzzedFractions)

        assertThat(fractions).contains(100f)
        assertThat(fractions).contains(500.1f)
        assertThat(fractions).contains(500.2f)
        assertThat(fractions).contains(0.2f)
        assertThat(fractions).contains(0f)
        assertThat(fractions).contains(-10f)
        assertThat(fractions).contains(-10f)
        assertThat(fractions).contains(-10.3f)
    }

    companion object {
        private const val CONVERSION_TOLERANCE = 0.05f
    }
}

fun generateSequenceOfFractions(
    range: ClosedFloatingPointRange<Float>,
    step: Float
): Sequence<Float> {
    val multiplier = 1f / step
    val start = floor(range.start * multiplier).toInt()
    val endInclusive = ceil(range.endInclusive * multiplier).toInt()
    return generateSequence(start) { it + 1 }
        .takeWhile { it <= endInclusive }
        .map{ it.toFloat() / multiplier }
}

private fun Sequence<Float>.fuzzFractions(): Sequence<Float> {
    return flatMap { i ->
        listOf(i, i + 0.01f, i + 0.054f, i + 0.099f, i + nextFloat(), i + nextFloat())
    }
}
