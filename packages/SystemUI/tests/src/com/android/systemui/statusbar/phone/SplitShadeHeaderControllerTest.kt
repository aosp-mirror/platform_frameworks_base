package com.android.systemui.statusbar.phone

import android.content.Context
import android.content.res.Resources
import android.test.suitebuilder.annotation.SmallTest
import android.view.View
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
class SplitShadeHeaderControllerTest : SysuiTestCase() {

    @Mock private lateinit var view: View
    @Mock private lateinit var statusIcons: StatusIconContainer
    @Mock private lateinit var statusBarIconController: StatusBarIconController
    @Mock private lateinit var qsCarrierGroupController: QSCarrierGroupController
    @Mock private lateinit var qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var batteryMeterView: BatteryMeterView
    @Mock private lateinit var batteryMeterViewController: BatteryMeterViewController
    @Mock private lateinit var resources: Resources
    @Mock private lateinit var context: Context
    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()
    var viewVisibility = View.GONE

    private lateinit var splitShadeHeaderController: SplitShadeHeaderController

    @Before
    fun setup() {
        whenever<BatteryMeterView>(view.findViewById(R.id.batteryRemainingIcon))
                .thenReturn(batteryMeterView)
        whenever<StatusIconContainer>(view.findViewById(R.id.statusIcons)).thenReturn(statusIcons)
        whenever(statusIcons.context).thenReturn(context)
        whenever(context.resources).thenReturn(resources)
        whenever(qsCarrierGroupControllerBuilder.setQSCarrierGroup(any()))
                .thenReturn(qsCarrierGroupControllerBuilder)
        whenever(qsCarrierGroupControllerBuilder.build()).thenReturn(qsCarrierGroupController)
        whenever(view.setVisibility(anyInt())).then {
            viewVisibility = it.arguments[0] as Int
            null
        }
        whenever(view.visibility).thenAnswer { _ -> viewVisibility }
        splitShadeHeaderController = SplitShadeHeaderController(view, statusBarIconController,
        qsCarrierGroupControllerBuilder, featureFlags, batteryMeterViewController)
    }

    @Test
    fun setVisible_onlyInSplitShade() {
        splitShadeHeaderController.splitShadeMode = true
        splitShadeHeaderController.shadeExpanded = true
        assertThat(viewVisibility).isEqualTo(View.VISIBLE)

        splitShadeHeaderController.splitShadeMode = false
        assertThat(viewVisibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateListeners_registersWhenVisible() {
        splitShadeHeaderController.splitShadeMode = true
        splitShadeHeaderController.shadeExpanded = true
        verify(qsCarrierGroupController).setListening(true)
        verify(statusBarIconController).addIconGroup(any())
    }

    @Test
    fun shadeExpandedFraction_updatesAlpha() {
        splitShadeHeaderController.splitShadeMode = true
        splitShadeHeaderController.shadeExpanded = true
        splitShadeHeaderController.shadeExpandedFraction = 0.5f
        verify(view).setAlpha(ShadeInterpolation.getContentAlpha(0.5f))
    }
}