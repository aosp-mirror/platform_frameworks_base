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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontScaleConverterTest {

    @Test
    fun straightInterpolation() {
        val table = createTable(8f to 8f, 10f to 10f, 20f to 20f)
        assertThat(table.convertSpToDp(1F)).isWithin(CONVERSION_TOLERANCE).of(1f)
        assertThat(table.convertSpToDp(8F)).isWithin(CONVERSION_TOLERANCE).of(8f)
        assertThat(table.convertSpToDp(10F)).isWithin(CONVERSION_TOLERANCE).of(10f)
        assertThat(table.convertSpToDp(30F)).isWithin(CONVERSION_TOLERANCE).of(30f)
        assertThat(table.convertSpToDp(20F)).isWithin(CONVERSION_TOLERANCE).of(20f)
        assertThat(table.convertSpToDp(5F)).isWithin(CONVERSION_TOLERANCE).of(5f)
        assertThat(table.convertSpToDp(0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
    }

    @Test
    fun interpolate200Percent() {
        val table = createTable(8f to 16f, 10f to 20f, 30f to 60f)
        assertThat(table.convertSpToDp(1F)).isWithin(CONVERSION_TOLERANCE).of(2f)
        assertThat(table.convertSpToDp(8F)).isWithin(CONVERSION_TOLERANCE).of(16f)
        assertThat(table.convertSpToDp(10F)).isWithin(CONVERSION_TOLERANCE).of(20f)
        assertThat(table.convertSpToDp(30F)).isWithin(CONVERSION_TOLERANCE).of(60f)
        assertThat(table.convertSpToDp(20F)).isWithin(CONVERSION_TOLERANCE).of(40f)
        assertThat(table.convertSpToDp(5F)).isWithin(CONVERSION_TOLERANCE).of(10f)
        assertThat(table.convertSpToDp(0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
    }

    @Test
    fun interpolate150Percent() {
        val table = createTable(2f to 3f, 10f to 15f, 20f to 30f, 100f to 150f)
        assertThat(table.convertSpToDp(2F)).isWithin(CONVERSION_TOLERANCE).of(3f)
        assertThat(table.convertSpToDp(1F)).isWithin(CONVERSION_TOLERANCE).of(1.5f)
        assertThat(table.convertSpToDp(8F)).isWithin(CONVERSION_TOLERANCE).of(12f)
        assertThat(table.convertSpToDp(10F)).isWithin(CONVERSION_TOLERANCE).of(15f)
        assertThat(table.convertSpToDp(20F)).isWithin(CONVERSION_TOLERANCE).of(30f)
        assertThat(table.convertSpToDp(50F)).isWithin(CONVERSION_TOLERANCE).of(75f)
        assertThat(table.convertSpToDp(5F)).isWithin(CONVERSION_TOLERANCE).of(7.5f)
        assertThat(table.convertSpToDp(0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
    }

    @Test
    fun pastEndsUsesLastScalingFactor() {
        val table = createTable(8f to 16f, 10f to 20f, 30f to 60f)
        assertThat(table.convertSpToDp(100F)).isWithin(CONVERSION_TOLERANCE).of(200f)
        assertThat(table.convertSpToDp(31F)).isWithin(CONVERSION_TOLERANCE).of(62f)
        assertThat(table.convertSpToDp(1000F)).isWithin(CONVERSION_TOLERANCE).of(2000f)
        assertThat(table.convertSpToDp(2000F)).isWithin(CONVERSION_TOLERANCE).of(4000f)
        assertThat(table.convertSpToDp(10000F)).isWithin(CONVERSION_TOLERANCE).of(20000f)
    }

    @Test
    fun negativeSpIsNegativeDp() {
        val table = createTable(8f to 16f, 10f to 20f, 30f to 60f)
        assertThat(table.convertSpToDp(-1F)).isWithin(CONVERSION_TOLERANCE).of(-2f)
        assertThat(table.convertSpToDp(-8F)).isWithin(CONVERSION_TOLERANCE).of(-16f)
        assertThat(table.convertSpToDp(-10F)).isWithin(CONVERSION_TOLERANCE).of(-20f)
        assertThat(table.convertSpToDp(-30F)).isWithin(CONVERSION_TOLERANCE).of(-60f)
        assertThat(table.convertSpToDp(-20F)).isWithin(CONVERSION_TOLERANCE).of(-40f)
        assertThat(table.convertSpToDp(-5F)).isWithin(CONVERSION_TOLERANCE).of(-10f)
        assertThat(table.convertSpToDp(-0F)).isWithin(CONVERSION_TOLERANCE).of(0f)
    }

    private fun createTable(vararg pairs: Pair<Float, Float>) =
        FontScaleConverter(
            pairs.map { it.first }.toFloatArray(),
            pairs.map { it.second }.toFloatArray()
        )

    companion object {
        private const val CONVERSION_TOLERANCE = 0.05f
    }
}
