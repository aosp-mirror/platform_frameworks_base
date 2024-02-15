package com.android.systemui.statusbar.notification

import android.platform.test.annotations.EnableFlags
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.shared.NotificationsImprovedHunAnimation
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(JUnit4::class)
class RoundableTest : SysuiTestCase() {
    private val targetView: View = mock()
    private val roundable = FakeRoundable(targetView = targetView)

    @Test
    fun defaultConfig_shouldNotHaveRoundedCorner() {
        // the expected default value for the roundness is top = 0f, bottom = 0f
        assertEquals(0f, roundable.roundableState.topRoundness)
        assertEquals(0f, roundable.roundableState.bottomRoundness)
        assertEquals(false, roundable.hasRoundedCorner())
    }

    @Test
    fun applyRoundnessAndInvalidate_should_invalidate_targetView() {
        roundable.applyRoundnessAndInvalidate()

        verify(targetView, times(1)).invalidate()
    }

    @Test
    fun requestTopRoundness_update_and_invalidate_targetView() {
        roundable.requestTopRoundness(value = 1f, sourceType = SOURCE1)

        assertEquals(1f, roundable.roundableState.topRoundness)
        verify(targetView, times(1)).invalidate()
    }

    @Test
    fun requestBottomRoundness_update_and_invalidate_targetView() {
        roundable.requestBottomRoundness(value = 1f, sourceType = SOURCE1)

        assertEquals(1f, roundable.roundableState.bottomRoundness)
        verify(targetView, times(1)).invalidate()
    }

    @Test
    fun requestRoundness_update_and_invalidate_targetView() {
        roundable.requestRoundness(top = 1f, bottom = 1f, sourceType = SOURCE1)

        assertEquals(1f, roundable.roundableState.topRoundness)
        assertEquals(1f, roundable.roundableState.bottomRoundness)
        verify(targetView, atLeastOnce()).invalidate()
    }

    @Test
    fun requestRoundnessReset_update_and_invalidate_targetView() {
        roundable.requestRoundness(1f, 1f, SOURCE1)
        assertEquals(1f, roundable.roundableState.topRoundness)
        assertEquals(1f, roundable.roundableState.bottomRoundness)

        roundable.requestRoundnessReset(sourceType = SOURCE1)

        assertEquals(0f, roundable.roundableState.topRoundness)
        assertEquals(0f, roundable.roundableState.bottomRoundness)
        verify(targetView, atLeastOnce()).invalidate()
    }

    @Test
    fun hasRoundedCorner_return_true_ifRoundnessIsGreaterThenZero() {
        roundable.requestRoundness(top = 1f, bottom = 1f, sourceType = SOURCE1)
        assertEquals(true, roundable.hasRoundedCorner())

        roundable.requestRoundness(top = 1f, bottom = 0f, sourceType = SOURCE1)
        assertEquals(true, roundable.hasRoundedCorner())

        roundable.requestRoundness(top = 0f, bottom = 1f, sourceType = SOURCE1)
        assertEquals(true, roundable.hasRoundedCorner())

        roundable.requestRoundness(top = 0f, bottom = 0f, sourceType = SOURCE1)
        assertEquals(false, roundable.hasRoundedCorner())
    }

    @Test
    fun roundness_take_maxValue_onMultipleSources_first_lower() {
        roundable.requestRoundness(0.1f, 0.1f, SOURCE1)
        assertEquals(0.1f, roundable.roundableState.topRoundness)
        assertEquals(0.1f, roundable.roundableState.bottomRoundness)

        roundable.requestRoundness(0.2f, 0.2f, SOURCE2)
        // SOURCE1 has 0.1f - SOURCE2 has 0.2f
        assertEquals(0.2f, roundable.roundableState.topRoundness)
        assertEquals(0.2f, roundable.roundableState.bottomRoundness)
    }

    @Test
    fun roundness_take_maxValue_onMultipleSources_first_higher() {
        roundable.requestRoundness(0.5f, 0.5f, SOURCE1)
        assertEquals(0.5f, roundable.roundableState.topRoundness)
        assertEquals(0.5f, roundable.roundableState.bottomRoundness)

        roundable.requestRoundness(0.1f, 0.1f, SOURCE2)
        // SOURCE1 has 0.5f - SOURCE2 has 0.1f
        assertEquals(0.5f, roundable.roundableState.topRoundness)
        assertEquals(0.5f, roundable.roundableState.bottomRoundness)
    }

    @Test
    fun roundness_take_maxValue_onMultipleSources_first_higher_second_step() {
        roundable.requestRoundness(0.1f, 0.1f, SOURCE1)
        assertEquals(0.1f, roundable.roundableState.topRoundness)
        assertEquals(0.1f, roundable.roundableState.bottomRoundness)

        roundable.requestRoundness(0.2f, 0.2f, SOURCE2)
        // SOURCE1 has 0.1f - SOURCE2 has 0.2f
        assertEquals(0.2f, roundable.roundableState.topRoundness)
        assertEquals(0.2f, roundable.roundableState.bottomRoundness)

        roundable.requestRoundness(0.3f, 0.3f, SOURCE1)
        // SOURCE1 has 0.3f - SOURCE2 has 0.2f
        assertEquals(0.3f, roundable.roundableState.topRoundness)
        assertEquals(0.3f, roundable.roundableState.bottomRoundness)
    }

    @Test
    fun roundness_take_maxValue_onMultipleSources_first_lower_second_step() {
        roundable.requestRoundness(0.5f, 0.5f, SOURCE1)
        assertEquals(0.5f, roundable.roundableState.topRoundness)
        assertEquals(0.5f, roundable.roundableState.bottomRoundness)

        roundable.requestRoundness(0.2f, 0.2f, SOURCE2)
        // SOURCE1 has 0.5f - SOURCE2 has 0.2f
        assertEquals(0.5f, roundable.roundableState.topRoundness)
        assertEquals(0.5f, roundable.roundableState.bottomRoundness)

        roundable.requestRoundness(0.1f, 0.1f, SOURCE1)
        // SOURCE1 has 0.1f - SOURCE2 has 0.2f
        assertEquals(0.2f, roundable.roundableState.topRoundness)
        assertEquals(0.2f, roundable.roundableState.bottomRoundness)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun getCornerRadii_radius_maxed_to_height() {
        whenever(targetView.height).thenReturn(10)
        roundable.requestRoundness(1f, 1f, SOURCE1)

        assertCornerRadiiEquals(5f, 5f)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun getCornerRadii_topRadius_maxed_to_height() {
        whenever(targetView.height).thenReturn(5)
        roundable.requestRoundness(1f, 0f, SOURCE1)

        assertCornerRadiiEquals(5f, 0f)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun getCornerRadii_bottomRadius_maxed_to_height() {
        whenever(targetView.height).thenReturn(5)
        roundable.requestRoundness(0f, 1f, SOURCE1)

        assertCornerRadiiEquals(0f, 5f)
    }

    @Test
    @EnableFlags(NotificationsImprovedHunAnimation.FLAG_NAME)
    fun getCornerRadii_radii_kept() {
        whenever(targetView.height).thenReturn(100)
        roundable.requestRoundness(1f, 1f, SOURCE1)

        assertCornerRadiiEquals(MAX_RADIUS, MAX_RADIUS)
    }

    private fun assertCornerRadiiEquals(top: Float, bottom: Float) {
        assertEquals("topCornerRadius", top, roundable.topCornerRadius)
        assertEquals("bottomCornerRadius", bottom, roundable.bottomCornerRadius)
    }

    class FakeRoundable(
        targetView: View,
        radius: Float = MAX_RADIUS,
    ) : Roundable {
        override val roundableState =
            RoundableState(
                targetView = targetView,
                roundable = this,
                maxRadius = radius,
            )

        override val clipHeight: Int
            get() = roundableState.targetView.height
    }

    companion object {
        private const val MAX_RADIUS = 10f
        private val SOURCE1 = SourceType.from("Source1")
        private val SOURCE2 = SourceType.from("Source2")
    }
}
