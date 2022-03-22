package com.android.systemui.statusbar.phone

import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.qs.HeaderPrivacyIconsController
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class LargeScreenShadeHeaderControllerTest : SysuiTestCase() {

    @Mock private lateinit var view: View
    @Mock private lateinit var statusIcons: StatusIconContainer
    @Mock private lateinit var statusBarIconController: StatusBarIconController
    @Mock private lateinit var qsCarrierGroupController: QSCarrierGroupController
    @Mock private lateinit var qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var batteryMeterView: BatteryMeterView
    @Mock private lateinit var batteryMeterViewController: BatteryMeterViewController
    @Mock private lateinit var privacyIconsController: HeaderPrivacyIconsController
    @Mock private lateinit var dumpManager: DumpManager

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()
    var viewVisibility = View.GONE

    private lateinit var mLargeScreenShadeHeaderController: LargeScreenShadeHeaderController
    private lateinit var carrierIconSlots: List<String>

    @Before
    fun setup() {
        whenever<BatteryMeterView>(view.findViewById(R.id.batteryRemainingIcon))
                .thenReturn(batteryMeterView)
        whenever<StatusIconContainer>(view.findViewById(R.id.statusIcons)).thenReturn(statusIcons)
        whenever(view.context).thenReturn(context)
        whenever(statusIcons.context).thenReturn(context)
        whenever(qsCarrierGroupControllerBuilder.setQSCarrierGroup(any()))
                .thenReturn(qsCarrierGroupControllerBuilder)
        whenever(qsCarrierGroupControllerBuilder.build()).thenReturn(qsCarrierGroupController)
        whenever(view.setVisibility(anyInt())).then {
            viewVisibility = it.arguments[0] as Int
            null
        }
        whenever(view.visibility).thenAnswer { _ -> viewVisibility }
        whenever(featureFlags.isEnabled(Flags.COMBINED_QS_HEADERS)).thenReturn(false)
        mLargeScreenShadeHeaderController = LargeScreenShadeHeaderController(
                view,
                statusBarIconController,
                privacyIconsController,
                qsCarrierGroupControllerBuilder,
                featureFlags,
                batteryMeterViewController,
                dumpManager
        )
        carrierIconSlots = listOf(
                context.getString(com.android.internal.R.string.status_bar_mobile))
    }

    @Test
    fun setVisible_onlyWhenActive() {
        makeShadeVisible()
        assertThat(viewVisibility).isEqualTo(View.VISIBLE)

        mLargeScreenShadeHeaderController.active = false
        assertThat(viewVisibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateListeners_registersWhenVisible() {
        makeShadeVisible()
        verify(qsCarrierGroupController).setListening(true)
        verify(statusBarIconController).addIconGroup(any())
    }

    @Test
    fun shadeExpandedFraction_updatesAlpha() {
        makeShadeVisible()
        mLargeScreenShadeHeaderController.shadeExpandedFraction = 0.5f
        verify(view).setAlpha(ShadeInterpolation.getContentAlpha(0.5f))
    }

    @Test
    fun singleCarrier_enablesCarrierIconsInStatusIcons() {
        whenever(qsCarrierGroupController.isSingleCarrier).thenReturn(true)

        makeShadeVisible()

        verify(statusIcons).removeIgnoredSlots(carrierIconSlots)
    }

    @Test
    fun dualCarrier_disablesCarrierIconsInStatusIcons() {
        whenever(qsCarrierGroupController.isSingleCarrier).thenReturn(false)

        makeShadeVisible()

        verify(statusIcons).addIgnoredSlots(carrierIconSlots)
    }

    private fun makeShadeVisible() {
        mLargeScreenShadeHeaderController.active = true
        mLargeScreenShadeHeaderController.shadeExpanded = true
    }
}
