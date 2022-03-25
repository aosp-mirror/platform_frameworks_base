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
internal class FloatingRotationButtonPositionCalculatorTest(private val testCase: TestCase)
    : SysuiTestCase() {

    private val calculator = FloatingRotationButtonPositionCalculator(
        MARGIN_DEFAULT, MARGIN_TASKBAR_LEFT, MARGIN_TASKBAR_BOTTOM
    )

    @Test
    fun calculatePosition() {
        val position = calculator.calculatePosition(
            testCase.rotation,
            testCase.taskbarVisible,
            testCase.taskbarStashed
        )

        assertThat(position).isEqualTo(testCase.expectedPosition)
    }

    internal class TestCase(
        val rotation: Int,
        val taskbarVisible: Boolean,
        val taskbarStashed: Boolean,
        val expectedPosition: Position
    ) {
        override fun toString(): String =
            "when rotation = $rotation, " +
                "taskbarVisible = $taskbarVisible, " +
                "taskbarStashed = $taskbarStashed - " +
                "expected $expectedPosition"
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<TestCase> =
            listOf(
                TestCase(
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
                    rotation = Surface.ROTATION_90,
                    taskbarVisible = true,
                    taskbarStashed = false,
                    expectedPosition = Position(
                        gravity = Gravity.BOTTOM or Gravity.RIGHT,
                        translationX = -MARGIN_TASKBAR_LEFT,
                        translationY = -MARGIN_TASKBAR_BOTTOM
                    )
                )
            )

        private const val MARGIN_DEFAULT = 10
        private const val MARGIN_TASKBAR_LEFT = 20
        private const val MARGIN_TASKBAR_BOTTOM = 30
    }
}
