/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics.udfps

import android.graphics.Point
import android.graphics.Rect
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when` as whenEver

@SmallTest
@RunWith(Parameterized::class)
class EllipseOverlapDetectorTest(val testCase: TestCase) : SysuiTestCase() {
    val underTest = spy(EllipseOverlapDetector(neededPoints = 1))

    @Before
    fun setUp() {
        // Use one single center point for testing, required or total number of points may change
        whenEver(underTest.calculateSensorPoints(SENSOR))
            .thenReturn(listOf(Point(SENSOR.centerX(), SENSOR.centerY())))
    }

    @Test
    fun isGoodOverlap() {
        val touchData =
            TOUCH_DATA.copy(
                x = testCase.x.toFloat(),
                y = testCase.y.toFloat(),
                minor = testCase.minor,
                major = testCase.major
            )
        val actual = underTest.isGoodOverlap(touchData, SENSOR)

        assertThat(actual).isEqualTo(testCase.expected)
    }

    data class TestCase(
        val x: Int,
        val y: Int,
        val minor: Float,
        val major: Float,
        val expected: Boolean
    )

    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun data(): List<TestCase> =
            listOf(
                    genTestCases(
                        innerXs = listOf(SENSOR.left, SENSOR.right, SENSOR.centerX()),
                        innerYs = listOf(SENSOR.top, SENSOR.bottom, SENSOR.centerY()),
                        outerXs = listOf(SENSOR.left - 1, SENSOR.right + 1),
                        outerYs = listOf(SENSOR.top - 1, SENSOR.bottom + 1),
                        minor = 300f,
                        major = 300f,
                        expected = true
                    ),
                    genTestCases(
                        innerXs = listOf(SENSOR.left, SENSOR.right),
                        innerYs = listOf(SENSOR.top, SENSOR.bottom),
                        outerXs = listOf(SENSOR.left - 1, SENSOR.right + 1),
                        outerYs = listOf(SENSOR.top - 1, SENSOR.bottom + 1),
                        minor = 100f,
                        major = 100f,
                        expected = false
                    )
                )
                .flatten()
    }
}

/* Placeholder touch parameters. */
private const val POINTER_ID = 42
private const val NATIVE_MINOR = 2.71828f
private const val NATIVE_MAJOR = 3.14f
private const val ORIENTATION = 0f // used for perfect circles
private const val TIME = 12345699L
private const val GESTURE_START = 12345600L

/* Template [NormalizedTouchData]. */
private val TOUCH_DATA =
    NormalizedTouchData(
        POINTER_ID,
        x = 0f,
        y = 0f,
        NATIVE_MINOR,
        NATIVE_MAJOR,
        ORIENTATION,
        TIME,
        GESTURE_START
    )

private val SENSOR = Rect(100 /* left */, 200 /* top */, 300 /* right */, 400 /* bottom */)

private fun genTestCases(
    innerXs: List<Int>,
    innerYs: List<Int>,
    outerXs: List<Int>,
    outerYs: List<Int>,
    minor: Float,
    major: Float,
    expected: Boolean
): List<EllipseOverlapDetectorTest.TestCase> {
    return (innerXs + outerXs).flatMap { x ->
        (innerYs + outerYs).map { y ->
            EllipseOverlapDetectorTest.TestCase(x, y, minor, major, expected)
        }
    }
}
