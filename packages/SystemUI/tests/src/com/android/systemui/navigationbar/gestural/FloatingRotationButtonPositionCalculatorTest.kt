package com.android.systemui.navigationbar.gestural

import android.view.Gravity
import android.view.Surface
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.rotation.FloatingRotationButtonPositionCalculator
import com.android.systemui.shared.rotation.FloatingRotationButtonPositionCalculator.Position
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@SmallTest
internal class FloatingRotationButtonPositionCalculatorTest(
        private val testCase: TestCase,
) : SysuiTestCase() {

    @Test
    fun calculatePosition() {
        val position = testCase.calculator.calculatePosition(
            testCase.rotation,
            testCase.taskbarVisible,
            testCase.taskbarStashed
        )
        assertThat(position).isEqualTo(testCase.expectedPosition)
    }

    internal class TestCase(
        val calculator: FloatingRotationButtonPositionCalculator,
        val rotation: Int,
        val taskbarVisible: Boolean,
        val taskbarStashed: Boolean,
        val expectedPosition: Position
    ) {
        override fun toString(): String =
                buildString {
                    append("when calculator = ")
                    append(when (calculator) {
                        posLeftCalculator -> "LEFT"
                        posRightCalculator -> "RIGHT"
                        else -> error("Unknown calculator: $calculator")
                    })
                    append(", rotation = $rotation")
                    append(", taskbarVisible = $taskbarVisible")
                    append(", taskbarStashed = $taskbarStashed")
                    append(" - expected $expectedPosition")
                }
    }

    companion object {
        private const val MARGIN_DEFAULT = 10
        private const val MARGIN_TASKBAR_LEFT = 20
        private const val MARGIN_TASKBAR_BOTTOM = 30

        private val posLeftCalculator = FloatingRotationButtonPositionCalculator(
            MARGIN_DEFAULT, MARGIN_TASKBAR_LEFT, MARGIN_TASKBAR_BOTTOM, true
        )
        private val posRightCalculator = FloatingRotationButtonPositionCalculator(
            MARGIN_DEFAULT, MARGIN_TASKBAR_LEFT, MARGIN_TASKBAR_BOTTOM, false
        )

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<TestCase> =
            listOf(
                // Position left
                TestCase(
                    calculator = posLeftCalculator,
                    rotation = Surface.ROTATION_0,
                    taskbarVisible = false,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.LEFT,
                        translationX = MARGIN_DEFAULT,
                        translationY = -MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posLeftCalculator,
                    rotation = Surface.ROTATION_90,
                    taskbarVisible = false,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.RIGHT,
                        translationX = -MARGIN_DEFAULT,
                        translationY = -MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posLeftCalculator,
                    rotation = Surface.ROTATION_180,
                    taskbarVisible = false,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.TOP or Gravity.RIGHT,
                        translationX = -MARGIN_DEFAULT,
                        translationY = MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posLeftCalculator,
                    rotation = Surface.ROTATION_270,
                    taskbarVisible = false,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.TOP or Gravity.LEFT,
                        translationX = MARGIN_DEFAULT,
                        translationY = MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posLeftCalculator,
                    rotation = Surface.ROTATION_0,
                    taskbarVisible = true,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.LEFT,
                        translationX = MARGIN_TASKBAR_LEFT,
                        translationY = -MARGIN_TASKBAR_BOTTOM
                    )
                ),
                TestCase(
                    calculator = posLeftCalculator,
                    rotation = Surface.ROTATION_0,
                    taskbarVisible = true,
                    taskbarStashed = true,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.LEFT,
                        translationX = MARGIN_DEFAULT,
                        translationY = -MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posLeftCalculator,
                    rotation = Surface.ROTATION_90,
                    taskbarVisible = true,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.RIGHT,
                        translationX = -MARGIN_TASKBAR_LEFT,
                        translationY = -MARGIN_TASKBAR_BOTTOM
                    )
                ),

                // Position right
                TestCase(
                    calculator = posRightCalculator,
                    rotation = Surface.ROTATION_0,
                    taskbarVisible = false,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.RIGHT,
                        translationX = -MARGIN_DEFAULT,
                        translationY = -MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posRightCalculator,
                    rotation = Surface.ROTATION_90,
                    taskbarVisible = false,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.TOP or Gravity.RIGHT,
                        translationX = -MARGIN_DEFAULT,
                        translationY = MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posRightCalculator,
                    rotation = Surface.ROTATION_180,
                    taskbarVisible = false,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.TOP or Gravity.LEFT,
                        translationX = MARGIN_DEFAULT,
                        translationY = MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posRightCalculator,
                    rotation = Surface.ROTATION_270,
                    taskbarVisible = false,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.LEFT,
                        translationX = MARGIN_DEFAULT,
                        translationY = -MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posRightCalculator,
                    rotation = Surface.ROTATION_0,
                    taskbarVisible = true,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.RIGHT,
                        translationX = -MARGIN_TASKBAR_LEFT,
                        translationY = -MARGIN_TASKBAR_BOTTOM
                    )
                ),
                TestCase(
                    calculator = posRightCalculator,
                    rotation = Surface.ROTATION_0,
                    taskbarVisible = true,
                    taskbarStashed = true,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.RIGHT,
                        translationX = -MARGIN_DEFAULT,
                        translationY = -MARGIN_DEFAULT
                    )
                ),
                TestCase(
                    calculator = posRightCalculator,
                    rotation = Surface.ROTATION_90,
                    taskbarVisible = true,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.TOP or Gravity.RIGHT,
                        translationX = -MARGIN_TASKBAR_LEFT,
                        translationY = MARGIN_TASKBAR_BOTTOM
                    )
                )
            )
    }
}
