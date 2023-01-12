package com.android.systemui.shade

import android.app.StatusBarManager
import android.content.Context
import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewPropertyAnimator
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Interpolators
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.qs.HeaderPrivacyIconsController
import com.android.systemui.qs.carrier.QSCarrierGroup
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.VariableDateViewController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
class LargeScreenShadeHeaderControllerTest : SysuiTestCase() {

    @Mock private lateinit var view: View
    @Mock private lateinit var statusIcons: StatusIconContainer
    @Mock private lateinit var statusBarIconController: StatusBarIconController
    @Mock private lateinit var iconManagerFactory: StatusBarIconController.TintedIconManager.Factory
    @Mock private lateinit var iconManager: StatusBarIconController.TintedIconManager
    @Mock private lateinit var qsCarrierGroupController: QSCarrierGroupController
    @Mock private lateinit var qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var clock: Clock
    @Mock private lateinit var date: TextView
    @Mock private lateinit var carrierGroup: QSCarrierGroup
    @Mock private lateinit var batteryMeterView: BatteryMeterView
    @Mock private lateinit var batteryMeterViewController: BatteryMeterViewController
    @Mock private lateinit var privacyIconsController: HeaderPrivacyIconsController
    @Mock private lateinit var insetsProvider: StatusBarContentInsetsProvider
    @Mock private lateinit var variableDateViewControllerFactory: VariableDateViewController.Factory
    @Mock private lateinit var variableDateViewController: VariableDateViewController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var combinedShadeHeadersConstraintManager:
        CombinedShadeHeadersConstraintManager

    @Mock private lateinit var mockedContext: Context
    @Mock private lateinit var demoModeController: DemoModeController

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()
    var viewVisibility = View.GONE

    private lateinit var mLargeScreenShadeHeaderController: LargeScreenShadeHeaderController
    private lateinit var carrierIconSlots: List<String>
    private val configurationController = FakeConfigurationController()

    @Before
    fun setup() {
        whenever<Clock>(view.findViewById(R.id.clock)).thenReturn(clock)
        whenever(clock.context).thenReturn(mockedContext)
        whenever<TextView>(view.findViewById(R.id.date)).thenReturn(date)
        whenever(date.context).thenReturn(mockedContext)
        whenever<QSCarrierGroup>(view.findViewById(R.id.carrier_group)).thenReturn(carrierGroup)
        whenever<BatteryMeterView>(view.findViewById(R.id.batteryRemainingIcon))
                .thenReturn(batteryMeterView)
        whenever<StatusIconContainer>(view.findViewById(R.id.statusIcons)).thenReturn(statusIcons)
        whenever(view.context).thenReturn(context)
        whenever(view.resources).thenReturn(context.resources)
        whenever(statusIcons.context).thenReturn(context)
        whenever(qsCarrierGroupControllerBuilder.setQSCarrierGroup(any()))
                .thenReturn(qsCarrierGroupControllerBuilder)
        whenever(qsCarrierGroupControllerBuilder.build()).thenReturn(qsCarrierGroupController)
        whenever(view.setVisibility(anyInt())).then {
            viewVisibility = it.arguments[0] as Int
            null
        }
        whenever(view.visibility).thenAnswer { _ -> viewVisibility }
        whenever(variableDateViewControllerFactory.create(any()))
            .thenReturn(variableDateViewController)
        whenever(iconManagerFactory.create(any(), any())).thenReturn(iconManager)
        whenever(featureFlags.isEnabled(Flags.COMBINED_QS_HEADERS)).thenReturn(false)
        mLargeScreenShadeHeaderController = LargeScreenShadeHeaderController(
                view,
                statusBarIconController,
                iconManagerFactory,
                privacyIconsController,
                insetsProvider,
                configurationController,
                variableDateViewControllerFactory,
                batteryMeterViewController,
                dumpManager,
                featureFlags,
                qsCarrierGroupControllerBuilder,
                combinedShadeHeadersConstraintManager,
                demoModeController
                )
        whenever(view.isAttachedToWindow).thenReturn(true)
        mLargeScreenShadeHeaderController.init()
        carrierIconSlots = listOf(
                context.getString(com.android.internal.R.string.status_bar_mobile))
    }

    @After
    fun verifyEveryTest() {
        verifyZeroInteractions(combinedShadeHeadersConstraintManager)
    }

    @Test
    fun setVisible_onlyWhenActive() {
        makeShadeVisible()
        assertThat(viewVisibility).isEqualTo(View.VISIBLE)

        mLargeScreenShadeHeaderController.largeScreenActive = false
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
        mLargeScreenShadeHeaderController.largeScreenActive = true
        mLargeScreenShadeHeaderController.qsVisible = true
    }

    @Test
    fun updateConfig_changesFontStyle() {
        configurationController.notifyDensityOrFontScaleChanged()

        verify(clock).setTextAppearance(R.style.TextAppearance_QS_Status)
        verify(date).setTextAppearance(R.style.TextAppearance_QS_Status)
        verify(carrierGroup).updateTextAppearance(R.style.TextAppearance_QS_Status_Carriers)
    }

    @Test
    fun alarmIconIgnored() {
        verify(statusIcons).addIgnoredSlot(
                context.getString(com.android.internal.R.string.status_bar_alarm_clock)
        )
    }

    @Test
    fun animateOutOnStartCustomizing() {
        val animator = mock(ViewPropertyAnimator::class.java, Answers.RETURNS_SELF)
        val duration = 1000L
        whenever(view.animate()).thenReturn(animator)

        mLargeScreenShadeHeaderController.startCustomizingAnimation(show = true, duration)

        verify(animator).setDuration(duration)
        verify(animator).alpha(0f)
        verify(animator).setInterpolator(Interpolators.ALPHA_OUT)
        verify(animator).start()
    }

    @Test
    fun animateInOnEndCustomizing() {
        val animator = mock(ViewPropertyAnimator::class.java, Answers.RETURNS_SELF)
        val duration = 1000L
        whenever(view.animate()).thenReturn(animator)

        mLargeScreenShadeHeaderController.startCustomizingAnimation(show = false, duration)

        verify(animator).setDuration(duration)
        verify(animator).alpha(1f)
        verify(animator).setInterpolator(Interpolators.ALPHA_IN)
        verify(animator).start()
    }

    @Test
    fun demoMode_attachDemoMode() {
        val cb = argumentCaptor<DemoMode>()
        verify(demoModeController).addCallback(capture(cb))
        cb.value.onDemoModeStarted()
        verify(clock).onDemoModeStarted()
    }

    @Test
    fun demoMode_detachDemoMode() {
        mLargeScreenShadeHeaderController.simulateViewDetached()
        val cb = argumentCaptor<DemoMode>()
        verify(demoModeController).removeCallback(capture(cb))
        cb.value.onDemoModeFinished()
        verify(clock).onDemoModeFinished()
    }
}
