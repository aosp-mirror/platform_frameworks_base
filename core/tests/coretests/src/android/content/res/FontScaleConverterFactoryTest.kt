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

import androidx.core.util.forEach
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

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

    companion object {
        private const val CONVERSION_TOLERANCE = 0.05f
    }
}
