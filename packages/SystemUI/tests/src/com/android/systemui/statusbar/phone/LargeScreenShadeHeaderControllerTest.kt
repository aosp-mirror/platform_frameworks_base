package com.android.systemui.statusbar.phone

import android.app.StatusBarManager
import android.content.Context
import android.content.res.TypedArray
import android.testing.AndroidTestingRunner
import android.util.TypedValue.COMPLEX_UNIT_PX
import android.view.View
import android.widget.TextView
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
import com.android.systemui.qs.carrier.QSCarrierGroup
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
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
    @Mock private lateinit var clock: TextView
    @Mock private lateinit var date: TextView
    @Mock private lateinit var carrierGroup: QSCarrierGroup
    @Mock private lateinit var batteryMeterView: BatteryMeterView
    @Mock private lateinit var batteryMeterViewController: BatteryMeterViewController
    @Mock private lateinit var privacyIconsController: HeaderPrivacyIconsController
    @Mock private lateinit var dumpManager: DumpManager

    @Mock private lateinit var mockedContext: Context
    @Mock private lateinit var typedArray: TypedArray

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()
    var viewVisibility = View.GONE

    private lateinit var mLargeScreenShadeHeaderController: LargeScreenShadeHeaderController
    private lateinit var carrierIconSlots: List<String>
    private val configurationController = FakeConfigurationController()

    @Before
    fun setup() {
        whenever<TextView>(view.findViewById(R.id.clock)).thenReturn(clock)
        whenever(clock.context).thenReturn(mockedContext)
        whenever(mockedContext.obtainStyledAttributes(anyInt(), any())).thenReturn(typedArray)
        whenever<TextView>(view.findViewById(R.id.date)).thenReturn(date)
        whenever(date.context).thenReturn(mockedContext)
        whenever<QSCarrierGroup>(view.findViewById(R.id.carrier_group)).thenReturn(carrierGroup)
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
                configurationController,
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

    @Test
    fun disableQS_notDisabled_visible() {
        makeShadeVisible()
        mLargeScreenShadeHeaderController.disable(0, 0, false)

        assertThat(viewVisibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun disableQS_disabled_gone() {
        makeShadeVisible()
        mLargeScreenShadeHeaderController.disable(0, StatusBarManager.DISABLE2_QUICK_SETTINGS,
            false)

        assertThat(viewVisibility).isEqualTo(View.GONE)
    }

    private fun makeShadeVisible() {
        mLargeScreenShadeHeaderController.active = true
        mLargeScreenShadeHeaderController.shadeExpanded = true
    }

    @Test
    fun updateConfig_changesFontSize() {
        val updatedTextPixelSize = 32
        setReturnTextSize(updatedTextPixelSize)

        configurationController.notifyDensityOrFontScaleChanged()

        verify(clock).setTextSize(COMPLEX_UNIT_PX, updatedTextPixelSize.toFloat())
        verify(date).setTextSize(COMPLEX_UNIT_PX, updatedTextPixelSize.toFloat())
        verify(carrierGroup).updateTextAppearance(R.style.TextAppearance_QS_Status)
    }

    @Test
    fun updateConfig_changesFontSizeMultipleTimes() {
        val updatedTextPixelSize1 = 32
        setReturnTextSize(updatedTextPixelSize1)
        configurationController.notifyDensityOrFontScaleChanged()
        verify(clock).setTextSize(COMPLEX_UNIT_PX, updatedTextPixelSize1.toFloat())
        verify(date).setTextSize(COMPLEX_UNIT_PX, updatedTextPixelSize1.toFloat())
        verify(carrierGroup).updateTextAppearance(R.style.TextAppearance_QS_Status)
        clearInvocations(carrierGroup)

        val updatedTextPixelSize2 = 42
        setReturnTextSize(updatedTextPixelSize2)
        configurationController.notifyDensityOrFontScaleChanged()
        verify(clock).setTextSize(COMPLEX_UNIT_PX, updatedTextPixelSize2.toFloat())
        verify(date).setTextSize(COMPLEX_UNIT_PX, updatedTextPixelSize2.toFloat())
        verify(carrierGroup).updateTextAppearance(R.style.TextAppearance_QS_Status)
    }

    private fun setReturnTextSize(resultTextSize: Int) {
        whenever(typedArray.getDimensionPixelSize(anyInt(), anyInt())).thenReturn(resultTextSize)
    }
}
