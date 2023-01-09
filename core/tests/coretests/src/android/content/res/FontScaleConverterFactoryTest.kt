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

    @SmallTest
    fun missingLookupTableReturnsNull() {
        assertThat(FontScaleConverterFactory.forScale(3F)).isNull()
    }

    @SmallTest
    fun missingLookupTable105ReturnsNull() {
        assertThat(FontScaleConverterFactory.forScale(1.05F)).isNull()
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

    @LargeTest
    @Test
    fun allFeasibleScalesAndConversionsDoNotCrash() {
        generateSequenceOfFractions(-10f..10f, step = 0.01f)
            .mapNotNull{ FontScaleConverterFactory.forScale(it) }
            .flatMap{ table ->
                generateSequenceOfFractions(-2000f..2000f, step = 0.01f)
                    .map{ Pair(table, it) }
            }
            .forEach { (table, sp) ->
                try {
                    assertWithMessage(
                        "convertSpToDp(%s) on table: %s",
                        sp.toString(),
                        table.toString()
                    )
                        .that(table.convertSpToDp(sp))
                        .isFinite()
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
