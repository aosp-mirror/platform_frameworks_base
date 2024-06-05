package com.android.systemui.animation.back

import android.util.DisplayMetrics
import android.window.BackEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.dpToPx
import com.google.common.truth.Truth.assertThat
import junit.framework.TestCase.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private data class BackInput(val progressX: Float, val progressY: Float, val edge: Int)

@SmallTest
@RunWith(JUnit4::class)
class BackAnimationSpecTest : SysuiTestCase() {
    private var displayMetrics =
        DisplayMetrics().apply {
            widthPixels = 100
            heightPixels = 200
            density = 3f
        }

    @Test
    fun sysUi_floatingSystemSurfaces_animationValues() {
        val maxX = 19.0f
        val maxY = 14.0f
        val minScale = 0.9f

        val backAnimationSpec = BackAnimationSpec.floatingSystemSurfacesForSysUi { displayMetrics }

        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 0f, progressY = 0f, edge = BackEvent.EDGE_LEFT),
            expected = BackTransformation(translateX = 0f, translateY = 0f, scale = 1f),
        )
        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 1f, progressY = 0f, edge = BackEvent.EDGE_LEFT),
            expected = BackTransformation(translateX = -maxX, translateY = 0f, scale = minScale),
        )
        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 1f, progressY = 0f, edge = BackEvent.EDGE_RIGHT),
            expected = BackTransformation(translateX = maxX, translateY = 0f, scale = minScale),
        )
        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 1f, progressY = 1f, edge = BackEvent.EDGE_LEFT),
            expected = BackTransformation(translateX = -maxX, translateY = -maxY, scale = minScale),
        )
        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 0f, progressY = 1f, edge = BackEvent.EDGE_LEFT),
            expected = BackTransformation(translateX = 0f, translateY = -maxY, scale = 1f),
        )
        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 0f, progressY = -1f, edge = BackEvent.EDGE_LEFT),
            expected = BackTransformation(translateX = 0f, translateY = maxY, scale = 1f),
        )
    }

    @Test
    fun sysUi_bottomsheet_animationValues() {
        val minScale = 1 - 48.dpToPx(displayMetrics) / displayMetrics.widthPixels

        val backAnimationSpec = BackAnimationSpec.bottomSheetForSysUi { displayMetrics }

        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 0f, progressY = 0f, edge = BackEvent.EDGE_LEFT),
            expected =
                BackTransformation(
                    translateX = Float.NaN,
                    translateY = Float.NaN,
                    scale = 1f,
                    scalePivotPosition = ScalePivotPosition.BOTTOM_CENTER
                ),
        )
        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 1f, progressY = 0f, edge = BackEvent.EDGE_LEFT),
            expected =
                BackTransformation(
                    translateX = Float.NaN,
                    translateY = Float.NaN,
                    scale = minScale,
                    scalePivotPosition = ScalePivotPosition.BOTTOM_CENTER
                ),
        )
        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 1f, progressY = 0f, edge = BackEvent.EDGE_RIGHT),
            expected =
                BackTransformation(
                    translateX = Float.NaN,
                    translateY = Float.NaN,
                    scale = minScale,
                    scalePivotPosition = ScalePivotPosition.BOTTOM_CENTER
                ),
        )
        assertBackTransformation(
            backAnimationSpec = backAnimationSpec,
            backInput = BackInput(progressX = 1f, progressY = 1f, edge = BackEvent.EDGE_LEFT),
            expected =
                BackTransformation(
                    translateX = Float.NaN,
                    translateY = Float.NaN,
                    scale = minScale,
                    scalePivotPosition = ScalePivotPosition.BOTTOM_CENTER
                ),
        )
    }
}

private fun assertBackTransformation(
    backAnimationSpec: BackAnimationSpec,
    backInput: BackInput,
    expected: BackTransformation,
) {
    val actual = BackTransformation()
    backAnimationSpec.getBackTransformation(
        backEvent =
            BackEvent(
                /* touchX = */ 0f,
                /* touchY = */ 0f,
                /* progress = */ backInput.progressX,
                /* swipeEdge = */ backInput.edge,
            ),
        progressY = backInput.progressY,
        result = actual
    )

    val tolerance = 0f
    if (expected.translateX.isNaN()) {
        assertEquals(expected.translateX, actual.translateX)
    } else {
        assertThat(actual.translateX).isWithin(tolerance).of(expected.translateX)
    }
    if (expected.translateY.isNaN()) {
        assertEquals(expected.translateY, actual.translateY)
    } else {
        assertThat(actual.translateY).isWithin(tolerance).of(expected.translateY)
    }
    assertThat(actual.scale).isWithin(tolerance).of(expected.scale)
    assertEquals(expected.scalePivotPosition, actual.scalePivotPosition)
}
