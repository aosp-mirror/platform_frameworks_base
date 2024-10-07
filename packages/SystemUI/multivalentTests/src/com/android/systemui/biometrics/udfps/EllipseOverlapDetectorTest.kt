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

import android.graphics.Rect
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.EllipseOverlapDetectorParams
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.runner.parameterized.Parameter
import org.junit.runner.RunWith

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class EllipseOverlapDetectorTest(val testCase: TestCase) : SysuiTestCase() {
    val underTest =
        EllipseOverlapDetector(
            EllipseOverlapDetectorParams(minOverlap = .4f, targetSize = .2f, stepSize = 1)
        )

    @Test
    fun isGoodOverlap() {
        val touchData =
            TOUCH_DATA.copy(
                x = testCase.x.toFloat(),
                y = testCase.y.toFloat(),
                minor = testCase.minor,
                major = testCase.major
            )
        val actual = underTest.isGoodOverlap(touchData, SENSOR, OVERLAY)

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
                    genPositiveTestCases(
                        innerXs = listOf(SENSOR.left, SENSOR.right, SENSOR.centerX()),
                        innerYs = listOf(SENSOR.top, SENSOR.bottom, SENSOR.centerY()),
                        outerXs = listOf(SENSOR.left - 1, SENSOR.right + 1),
                        outerYs = listOf(SENSOR.top - 1, SENSOR.bottom + 1),
                        minor = 300f,
                        major = 300f,
                        expected = true
                    ),
                    genNegativeTestCase(
                        outerXs =
                            listOf(SENSOR.left - 1, SENSOR.right + 1, OVERLAY.left, OVERLAY.right),
                        outerYs =
                            listOf(SENSOR.top - 1, SENSOR.bottom + 1, OVERLAY.top, OVERLAY.bottom),
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
private val OVERLAY = Rect(0 /* left */, 100 /* top */, 400 /* right */, 600 /* bottom */)

private fun genPositiveTestCases(
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

private fun genNegativeTestCase(
    outerXs: List<Int>,
    outerYs: List<Int>,
    minor: Float,
    major: Float,
    expected: Boolean
): List<EllipseOverlapDetectorTest.TestCase> {
    return outerXs.flatMap { x ->
        outerYs.map { y -> EllipseOverlapDetectorTest.TestCase(x, y, minor, major, expected) }
    }
}
