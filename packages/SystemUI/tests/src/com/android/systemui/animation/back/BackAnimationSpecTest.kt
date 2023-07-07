package com.android.systemui.animation.back

import android.util.DisplayMetrics
import android.window.BackEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
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
        val maxX = 14.0f
        val maxY = 4.0f
        val minScale = 0.8f

        val backAnimationSpec = BackAnimationSpec.floatingSystemSurfacesForSysUi(displayMetrics)

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
    assertThat(actual.translateX).isWithin(tolerance).of(expected.translateX)
    assertThat(actual.translateY).isWithin(tolerance).of(expected.translateY)
    assertThat(actual.scale).isWithin(tolerance).of(expected.scale)
}
