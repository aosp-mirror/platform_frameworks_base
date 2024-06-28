package com.android.systemui.shade

import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.view.DisplayCutout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
class QsBatteryModeControllerTest : SysuiTestCase() {

    private companion object {
        val CENTER_TOP_CUTOUT: DisplayCutout =
            mock<DisplayCutout>().also {
                whenever(it.boundingRectTop).thenReturn(Rect(10, 0, 20, 10))
            }

        const val MOTION_LAYOUT_MAX_FRAME = 100
        const val QQS_START_FRAME = 14
        const val QS_END_FRAME = 58
    }

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()!!

    @Mock private lateinit var insetsProvider: StatusBarContentInsetsProvider
    @Mock private lateinit var mockedContext: Context
    @Mock private lateinit var mockedResources: Resources

    private lateinit var controller: QsBatteryModeController // under test

    @Before
    fun setup() {
        whenever(mockedContext.resources).thenReturn(mockedResources)
        whenever(mockedResources.getInteger(R.integer.fade_in_start_frame)).thenReturn(QS_END_FRAME)
        whenever(mockedResources.getInteger(R.integer.fade_out_complete_frame))
            .thenReturn(QQS_START_FRAME)

        controller = QsBatteryModeController(mockedContext, insetsProvider)
    }

    @Test
    fun returnsMODE_ONforQqsWithCenterCutout() {
        assertThat(
                controller.getBatteryMode(CENTER_TOP_CUTOUT, QQS_START_FRAME.prevFrameToFraction())
            )
            .isEqualTo(BatteryMeterView.MODE_ON)
    }

    @Test
    fun returnsMODE_ESTIMATEforQsWithCenterCutout() {
        assertThat(controller.getBatteryMode(CENTER_TOP_CUTOUT, QS_END_FRAME.nextFrameToFraction()))
            .isEqualTo(BatteryMeterView.MODE_ESTIMATE)
    }

    @Test
    fun returnsMODE_ONforQqsWithCornerCutout() {
        whenever(insetsProvider.currentRotationHasCornerCutout()).thenReturn(true)

        assertThat(
                controller.getBatteryMode(CENTER_TOP_CUTOUT, QQS_START_FRAME.prevFrameToFraction())
            )
            .isEqualTo(BatteryMeterView.MODE_ESTIMATE)
    }

    @Test
    fun returnsMODE_ESTIMATEforQsWithCornerCutout() {
        whenever(insetsProvider.currentRotationHasCornerCutout()).thenReturn(true)

        assertThat(controller.getBatteryMode(CENTER_TOP_CUTOUT, QS_END_FRAME.nextFrameToFraction()))
            .isEqualTo(BatteryMeterView.MODE_ESTIMATE)
    }

    @Test
    fun returnsNullInBetween() {
        assertThat(
                controller.getBatteryMode(CENTER_TOP_CUTOUT, QQS_START_FRAME.nextFrameToFraction())
            )
            .isNull()
        assertThat(controller.getBatteryMode(CENTER_TOP_CUTOUT, QS_END_FRAME.prevFrameToFraction()))
            .isNull()
    }

    private fun Int.prevFrameToFraction(): Float = (this - 1) / MOTION_LAYOUT_MAX_FRAME.toFloat()
    private fun Int.nextFrameToFraction(): Float = (this + 1) / MOTION_LAYOUT_MAX_FRAME.toFloat()
}
