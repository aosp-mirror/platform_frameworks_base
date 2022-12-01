package com.android.systemui.biometrics.udfps

import android.graphics.Rect
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

@SmallTest
@RunWith(Parameterized::class)
class NormalizedTouchDataTest(val testCase: TestCase) : SysuiTestCase() {

    @Test
    fun isWithinSensor() {
        val touchData = TOUCH_DATA.copy(x = testCase.x.toFloat(), y = testCase.y.toFloat())
        val actual = touchData.isWithinSensor(SENSOR)

        assertThat(actual).isEqualTo(testCase.expected)
    }

    data class TestCase(val x: Int, val y: Int, val expected: Boolean)

    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun data(): List<TestCase> =
            listOf(
                    genPositiveTestCases(
                        validXs = listOf(SENSOR.left, SENSOR.right, SENSOR.centerX()),
                        validYs = listOf(SENSOR.top, SENSOR.bottom, SENSOR.centerY())
                    ),
                    genNegativeTestCases(
                        invalidXs = listOf(SENSOR.left - 1, SENSOR.right + 1),
                        invalidYs = listOf(SENSOR.top - 1, SENSOR.bottom + 1),
                        validXs = listOf(SENSOR.left, SENSOR.right, SENSOR.centerX()),
                        validYs = listOf(SENSOR.top, SENSOR.bottom, SENSOR.centerY())
                    )
                )
                .flatten()
    }
}

/* Placeholder touch parameters. */
private const val POINTER_ID = 42
private const val NATIVE_MINOR = 2.71828f
private const val NATIVE_MAJOR = 3.14f
private const val ORIENTATION = 1.23f
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

private val SENSOR = Rect(100 /* left */, 200 /* top */, 300 /* right */, 500 /* bottom */)

private fun genTestCases(
    xs: List<Int>,
    ys: List<Int>,
    expected: Boolean
): List<NormalizedTouchDataTest.TestCase> {
    return xs.flatMap { x -> ys.map { y -> NormalizedTouchDataTest.TestCase(x, y, expected) } }
}

private fun genPositiveTestCases(
    validXs: List<Int>,
    validYs: List<Int>,
) = genTestCases(validXs, validYs, expected = true)

private fun genNegativeTestCases(
    invalidXs: List<Int>,
    invalidYs: List<Int>,
    validXs: List<Int>,
    validYs: List<Int>,
): List<NormalizedTouchDataTest.TestCase> {
    return genTestCases(invalidXs, validYs, expected = false) +
        genTestCases(validXs, invalidYs, expected = false)
}
