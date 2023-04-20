/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade

import android.content.Context
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.view.DisplayCutout
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.widget.TextView
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintSet
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
import com.android.systemui.qs.ChipVisibilityListener
import com.android.systemui.qs.HeaderPrivacyIconsController
import com.android.systemui.qs.carrier.QSCarrierGroup
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.shade.LargeScreenShadeHeaderController.Companion.HEADER_TRANSITION_ID
import com.android.systemui.shade.LargeScreenShadeHeaderController.Companion.LARGE_SCREEN_HEADER_CONSTRAINT
import com.android.systemui.shade.LargeScreenShadeHeaderController.Companion.QQS_HEADER_CONSTRAINT
import com.android.systemui.shade.LargeScreenShadeHeaderController.Companion.QS_HEADER_CONSTRAINT
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.VariableDateView
import com.android.systemui.statusbar.policy.VariableDateViewController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private val EMPTY_CHANGES = ConstraintsChanges()

/**
 * Tests for [LargeScreenShadeHeaderController] when [Flags.COMBINED_QS_HEADERS] is `true`.
 *
 * Once that flag is removed, this class will be combined with
 * [LargeScreenShadeHeaderControllerTest].
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class LargeScreenShadeHeaderControllerCombinedTest : SysuiTestCase() {

    @Mock
    private lateinit var statusIcons: StatusIconContainer
    @Mock
    private lateinit var statusBarIconController: StatusBarIconController
    @Mock
    private lateinit var iconManagerFactory: StatusBarIconController.TintedIconManager.Factory
    @Mock
    private lateinit var iconManager: StatusBarIconController.TintedIconManager
    @Mock
    private lateinit var qsCarrierGroupController: QSCarrierGroupController
    @Mock
    private lateinit var qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Mock
    private lateinit var clock: Clock
    @Mock
    private lateinit var date: VariableDateView
    @Mock
    private lateinit var carrierGroup: QSCarrierGroup
    @Mock
    private lateinit var batteryMeterView: BatteryMeterView
    @Mock
    private lateinit var batteryMeterViewController: BatteryMeterViewController
    @Mock
    private lateinit var privacyIconsController: HeaderPrivacyIconsController
    @Mock
    private lateinit var insetsProvider: StatusBarContentInsetsProvider
    @Mock
    private lateinit var variableDateViewControllerFactory: VariableDateViewController.Factory
    @Mock
    private lateinit var variableDateViewController: VariableDateViewController
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var combinedShadeHeadersConstraintManager:
        CombinedShadeHeadersConstraintManager

    @Mock
    private lateinit var mockedContext: Context
    @Mock(answer = Answers.RETURNS_MOCKS)
    private lateinit var view: MotionLayout

    @Mock
    private lateinit var qqsConstraints: ConstraintSet
    @Mock
    private lateinit var qsConstraints: ConstraintSet
    @Mock
    private lateinit var largeScreenConstraints: ConstraintSet
    @Mock private lateinit var demoModeController: DemoModeController

    @JvmField @Rule
    val mockitoRule = MockitoJUnit.rule()
    var viewVisibility = View.GONE

    private lateinit var controller: LargeScreenShadeHeaderController
    private lateinit var carrierIconSlots: List<String>
    private val configurationController = FakeConfigurationController()
    private lateinit var demoModeControllerCapture: ArgumentCaptor<DemoMode>

    @Before
    fun setUp() {
        demoModeControllerCapture = argumentCaptor<DemoMode>()
        whenever<Clock>(view.findViewById(R.id.clock)).thenReturn(clock)
        whenever(clock.context).thenReturn(mockedContext)

        whenever<TextView>(view.findViewById(R.id.date)).thenReturn(date)
        whenever(date.context).thenReturn(mockedContext)
        whenever(variableDateViewControllerFactory.create(any()))
            .thenReturn(variableDateViewController)

        whenever<QSCarrierGroup>(view.findViewById(R.id.carrier_group)).thenReturn(carrierGroup)
        whenever<BatteryMeterView>(view.findViewById(R.id.batteryRemainingIcon))
            .thenReturn(batteryMeterView)

        whenever<StatusIconContainer>(view.findViewById(R.id.statusIcons)).thenReturn(statusIcons)
        whenever(statusIcons.context).thenReturn(context)

        whenever(qsCarrierGroupControllerBuilder.setQSCarrierGroup(any()))
            .thenReturn(qsCarrierGroupControllerBuilder)
        whenever(qsCarrierGroupControllerBuilder.build()).thenReturn(qsCarrierGroupController)

        whenever(view.context).thenReturn(context)
        whenever(view.resources).thenReturn(context.resources)
        whenever(view.setVisibility(ArgumentMatchers.anyInt())).then {
            viewVisibility = it.arguments[0] as Int
            null
        }
        whenever(view.visibility).thenAnswer { _ -> viewVisibility }
        whenever(view.alpha).thenReturn(1f)

        whenever(iconManagerFactory.create(any(), any())).thenReturn(iconManager)

        whenever(featureFlags.isEnabled(Flags.COMBINED_QS_HEADERS)).thenReturn(true)

        setUpDefaultInsets()
        setUpMotionLayout(view)

        controller = LargeScreenShadeHeaderController(
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
        controller.init()
        carrierIconSlots = listOf(
            context.getString(com.android.internal.R.string.status_bar_mobile))
    }

    @Test
    fun testControllersCreatedAndInitialized() {
        verify(variableDateViewController).init()

        verify(batteryMeterViewController).init()
        verify(batteryMeterViewController).ignoreTunerUpdates()
        verify(batteryMeterView).setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)

        val inOrder = inOrder(qsCarrierGroupControllerBuilder)
        inOrder.verify(qsCarrierGroupControllerBuilder).setQSCarrierGroup(carrierGroup)
        inOrder.verify(qsCarrierGroupControllerBuilder).build()
    }

    @Test
    fun testClockPivotLtr() {
        val width = 200
        whenever(clock.width).thenReturn(width)
        whenever(clock.isLayoutRtl).thenReturn(false)

        val captor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(clock).addOnLayoutChangeListener(capture(captor))

        captor.value.onLayoutChange(clock, 0, 1, 2, 3, 4, 5, 6, 7)
        verify(clock).pivotX = 0f
    }

    @Test
    fun testClockPivotRtl() {
        val width = 200
        whenever(clock.width).thenReturn(width)
        whenever(clock.isLayoutRtl).thenReturn(true)

        val captor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(clock).addOnLayoutChangeListener(capture(captor))

        captor.value.onLayoutChange(clock, 0, 1, 2, 3, 4, 5, 6, 7)
        verify(clock).pivotX = width.toFloat()
    }

    @Test
    fun testShadeExpanded_true() {
        // When shade is expanded, view should be visible regardless of largeScreenActive
        controller.largeScreenActive = false
        controller.qsVisible = true
        assertThat(viewVisibility).isEqualTo(View.VISIBLE)

        controller.largeScreenActive = true
        assertThat(viewVisibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testShadeExpanded_false() {
        // When shade is not expanded, view should be invisible regardless of largeScreenActive
        controller.largeScreenActive = false
        controller.qsVisible = false
        assertThat(viewVisibility).isEqualTo(View.INVISIBLE)

        controller.largeScreenActive = true
        assertThat(viewVisibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun testLargeScreenActive_false() {
        controller.largeScreenActive = true // Make sure there's a change
        clearInvocations(view)

        controller.largeScreenActive = false

        verify(view).setTransition(HEADER_TRANSITION_ID)
    }

    @Test
    fun testShadeExpandedFraction() {
        // View needs to be visible for this to actually take effect
        controller.qsVisible = true

        clearInvocations(view)
        controller.shadeExpandedFraction = 0.3f
        verify(view).alpha = ShadeInterpolation.getContentAlpha(0.3f)

        clearInvocations(view)
        controller.shadeExpandedFraction = 1f
        verify(view).alpha = ShadeInterpolation.getContentAlpha(1f)

        clearInvocations(view)
        controller.shadeExpandedFraction = 0f
        verify(view).alpha = ShadeInterpolation.getContentAlpha(0f)
    }

    @Test
    fun testQsExpandedFraction_headerTransition() {
        controller.qsVisible = true
        controller.largeScreenActive = false

        clearInvocations(view)
        controller.qsExpandedFraction = 0.3f
        verify(view).progress = 0.3f
    }

    @Test
    fun testQsExpandedFraction_largeScreen() {
        controller.qsVisible = true
        controller.largeScreenActive = true

        clearInvocations(view)
        controller.qsExpandedFraction = 0.3f
        verify(view, never()).progress = anyFloat()
    }

    @Test
    fun testScrollY_headerTransition() {
        controller.largeScreenActive = false

        clearInvocations(view)
        controller.qsScrollY = 20
        verify(view).scrollY = 20
    }

    @Test
    fun testScrollY_largeScreen() {
        controller.largeScreenActive = true

        clearInvocations(view)
        controller.qsScrollY = 20
        verify(view, never()).scrollY = anyInt()
    }

    @Test
    fun testPrivacyChipVisibilityChanged_visible_changesCorrectConstraints() {
        val chipVisibleChanges = createMockConstraintChanges()
        val chipNotVisibleChanges = createMockConstraintChanges()

        whenever(combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(true))
            .thenReturn(chipVisibleChanges)
        whenever(combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(false))
            .thenReturn(chipNotVisibleChanges)

        val captor = ArgumentCaptor.forClass(ChipVisibilityListener::class.java)
        verify(privacyIconsController).chipVisibilityListener = capture(captor)

        captor.value.onChipVisibilityRefreshed(true)

        verify(chipVisibleChanges.qqsConstraintsChanges)!!.invoke(qqsConstraints)
        verify(chipVisibleChanges.qsConstraintsChanges)!!.invoke(qsConstraints)
        verify(chipVisibleChanges.largeScreenConstraintsChanges)!!.invoke(largeScreenConstraints)

        verify(chipNotVisibleChanges.qqsConstraintsChanges, never())!!.invoke(any())
        verify(chipNotVisibleChanges.qsConstraintsChanges, never())!!.invoke(any())
        verify(chipNotVisibleChanges.largeScreenConstraintsChanges, never())!!.invoke(any())
    }

    @Test
    fun testPrivacyChipVisibilityChanged_notVisible_changesCorrectConstraints() {
        val chipVisibleChanges = createMockConstraintChanges()
        val chipNotVisibleChanges = createMockConstraintChanges()

        whenever(combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(true))
            .thenReturn(chipVisibleChanges)
        whenever(combinedShadeHeadersConstraintManager.privacyChipVisibilityConstraints(false))
            .thenReturn(chipNotVisibleChanges)

        val captor = ArgumentCaptor.forClass(ChipVisibilityListener::class.java)
        verify(privacyIconsController).chipVisibilityListener = capture(captor)

        captor.value.onChipVisibilityRefreshed(false)

        verify(chipVisibleChanges.qqsConstraintsChanges, never())!!.invoke(qqsConstraints)
        verify(chipVisibleChanges.qsConstraintsChanges, never())!!.invoke(qsConstraints)
        verify(chipVisibleChanges.largeScreenConstraintsChanges, never())!!
            .invoke(largeScreenConstraints)

        verify(chipNotVisibleChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(chipNotVisibleChanges.qsConstraintsChanges)!!.invoke(any())
        verify(chipNotVisibleChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testInsetsGuides_ltr() {
        whenever(view.isLayoutRtl).thenReturn(false)
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        val (insetLeft, insetRight) = 30 to 40
        val (paddingStart, paddingEnd) = 10 to 20
        whenever(view.paddingStart).thenReturn(paddingStart)
        whenever(view.paddingEnd).thenReturn(paddingEnd)

        mockInsetsProvider(insetLeft to insetRight, false)

        whenever(combinedShadeHeadersConstraintManager
            .edgesGuidelinesConstraints(anyInt(), anyInt(), anyInt(), anyInt())
        ).thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets())

        verify(combinedShadeHeadersConstraintManager)
            .edgesGuidelinesConstraints(insetLeft, paddingStart, insetRight, paddingEnd)

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testInsetsGuides_rtl() {
        whenever(view.isLayoutRtl).thenReturn(true)
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        val (insetLeft, insetRight) = 30 to 40
        val (paddingStart, paddingEnd) = 10 to 20
        whenever(view.paddingStart).thenReturn(paddingStart)
        whenever(view.paddingEnd).thenReturn(paddingEnd)

        mockInsetsProvider(insetLeft to insetRight, false)

        whenever(combinedShadeHeadersConstraintManager
            .edgesGuidelinesConstraints(anyInt(), anyInt(), anyInt(), anyInt())
        ).thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets())

        verify(combinedShadeHeadersConstraintManager)
            .edgesGuidelinesConstraints(insetRight, paddingStart, insetLeft, paddingEnd)

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testNullCutout() {
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets(null))

        verify(combinedShadeHeadersConstraintManager).emptyCutoutConstraints()
        verify(combinedShadeHeadersConstraintManager, never())
            .centerCutoutConstraints(anyBoolean(), anyInt())

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testEmptyCutout() {
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets())

        verify(combinedShadeHeadersConstraintManager).emptyCutoutConstraints()
        verify(combinedShadeHeadersConstraintManager, never())
            .centerCutoutConstraints(anyBoolean(), anyInt())

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testCornerCutout_emptyRect() {
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        mockInsetsProvider(0 to 0, true)

        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets())

        verify(combinedShadeHeadersConstraintManager).emptyCutoutConstraints()
        verify(combinedShadeHeadersConstraintManager, never())
            .centerCutoutConstraints(anyBoolean(), anyInt())

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testCornerCutout_nonEmptyRect() {
        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        mockInsetsProvider(0 to 0, true)

        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets(Rect(1, 2, 3, 4)))

        verify(combinedShadeHeadersConstraintManager).emptyCutoutConstraints()
        verify(combinedShadeHeadersConstraintManager, never())
            .centerCutoutConstraints(anyBoolean(), anyInt())

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testTopCutout_ltr() {
        val width = 100
        val paddingLeft = 10
        val paddingRight = 20
        val cutoutWidth = 30

        whenever(view.isLayoutRtl).thenReturn(false)
        whenever(view.width).thenReturn(width)
        whenever(view.paddingLeft).thenReturn(paddingLeft)
        whenever(view.paddingRight).thenReturn(paddingRight)

        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        mockInsetsProvider(0 to 0, false)

        whenever(combinedShadeHeadersConstraintManager
            .centerCutoutConstraints(anyBoolean(), anyInt())
        ).thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets(Rect(0, 0, cutoutWidth, 1)))

        verify(combinedShadeHeadersConstraintManager, never()).emptyCutoutConstraints()
        val offset = (width - paddingLeft - paddingRight - cutoutWidth) / 2
        verify(combinedShadeHeadersConstraintManager).centerCutoutConstraints(false, offset)

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun testTopCutout_rtl() {
        val width = 100
        val paddingLeft = 10
        val paddingRight = 20
        val cutoutWidth = 30

        whenever(view.isLayoutRtl).thenReturn(true)
        whenever(view.width).thenReturn(width)
        whenever(view.paddingLeft).thenReturn(paddingLeft)
        whenever(view.paddingRight).thenReturn(paddingRight)

        val captor = ArgumentCaptor.forClass(View.OnApplyWindowInsetsListener::class.java)
        verify(view).setOnApplyWindowInsetsListener(capture(captor))
        val mockConstraintsChanges = createMockConstraintChanges()

        mockInsetsProvider(0 to 0, false)

        whenever(combinedShadeHeadersConstraintManager
            .centerCutoutConstraints(anyBoolean(), anyInt())
        ).thenReturn(mockConstraintsChanges)

        captor.value.onApplyWindowInsets(view, createWindowInsets(Rect(0, 0, cutoutWidth, 1)))

        verify(combinedShadeHeadersConstraintManager, never()).emptyCutoutConstraints()
        val offset = (width - paddingLeft - paddingRight - cutoutWidth) / 2
        verify(combinedShadeHeadersConstraintManager).centerCutoutConstraints(true, offset)

        verify(mockConstraintsChanges.qqsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.qsConstraintsChanges)!!.invoke(any())
        verify(mockConstraintsChanges.largeScreenConstraintsChanges)!!.invoke(any())
    }

    @Test
    fun alarmIconNotIgnored() {
        verify(statusIcons, never()).addIgnoredSlot(
                context.getString(com.android.internal.R.string.status_bar_alarm_clock)
        )
    }

    @Test
    fun demoMode_attachDemoMode() {
        verify(demoModeController).addCallback(capture(demoModeControllerCapture))
        demoModeControllerCapture.value.onDemoModeStarted()
        verify(clock).onDemoModeStarted()
    }

    @Test
    fun demoMode_detachDemoMode() {
        controller.simulateViewDetached()
        verify(demoModeController).removeCallback(capture(demoModeControllerCapture))
        demoModeControllerCapture.value.onDemoModeFinished()
        verify(clock).onDemoModeFinished()
    }

    @Test
    fun animateOutOnStartCustomizing() {
        val animator = Mockito.mock(ViewPropertyAnimator::class.java, Answers.RETURNS_SELF)
        val duration = 1000L
        whenever(view.animate()).thenReturn(animator)

        controller.startCustomizingAnimation(show = true, duration)

        verify(animator).setDuration(duration)
        verify(animator).alpha(0f)
        verify(animator).setInterpolator(Interpolators.ALPHA_OUT)
        verify(animator).start()
    }

    @Test
    fun animateInOnEndCustomizing() {
        val animator = Mockito.mock(ViewPropertyAnimator::class.java, Answers.RETURNS_SELF)
        val duration = 1000L
        whenever(view.animate()).thenReturn(animator)

        controller.startCustomizingAnimation(show = false, duration)

        verify(animator).setDuration(duration)
        verify(animator).alpha(1f)
        verify(animator).setInterpolator(Interpolators.ALPHA_IN)
        verify(animator).start()
    }

    @Test
    fun privacyChipParentVisibleFromStart() {
        verify(privacyIconsController).onParentVisible()
    }

    @Test
    fun privacyChipParentVisibleAlways() {
        controller.largeScreenActive = true
        controller.largeScreenActive = false
        controller.largeScreenActive = true

        verify(privacyIconsController, never()).onParentInvisible()
    }

    @Test
    fun clockPivotYInCenter() {
        val captor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(clock).addOnLayoutChangeListener(capture(captor))
        var height = 100
        val width = 50

        clock.executeLayoutChange(0, 0, width, height, captor.value)
        verify(clock).pivotY = height.toFloat() / 2

        height = 150
        clock.executeLayoutChange(0, 0, width, height, captor.value)
        verify(clock).pivotY = height.toFloat() / 2
    }

    @Test
    fun onDensityOrFontScaleChanged_reloadConstraints() {
        // After density or font scale change, constraints need to be reloaded to reflect new
        // dimensions.
        reset(qqsConstraints)
        reset(qsConstraints)
        reset(largeScreenConstraints)

        configurationController.notifyDensityOrFontScaleChanged()

        val captor = ArgumentCaptor.forClass(XmlResourceParser::class.java)
        verify(qqsConstraints).load(eq(context), capture(captor))
        assertThat(captor.value.getResId()).isEqualTo(R.xml.qqs_header)
        verify(qsConstraints).load(eq(context), capture(captor))
        assertThat(captor.value.getResId()).isEqualTo(R.xml.qs_header)
        verify(largeScreenConstraints).load(eq(context), capture(captor))
        assertThat(captor.value.getResId()).isEqualTo(R.xml.large_screen_shade_header)
    }

    private fun View.executeLayoutChange(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            listener: View.OnLayoutChangeListener
    ) {
        val oldLeft = this.left
        val oldTop = this.top
        val oldRight = this.right
        val oldBottom = this.bottom
        whenever(this.left).thenReturn(left)
        whenever(this.top).thenReturn(top)
        whenever(this.right).thenReturn(right)
        whenever(this.bottom).thenReturn(bottom)
        whenever(this.height).thenReturn(bottom - top)
        whenever(this.width).thenReturn(right - left)
        listener.onLayoutChange(
                this,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom,
                left,
                top,
                right,
                bottom
        )
    }

    private fun createWindowInsets(
        topCutout: Rect? = Rect()
    ): WindowInsets {
        val windowInsets: WindowInsets = mock()
        val displayCutout: DisplayCutout = mock()
        whenever(windowInsets.displayCutout)
            .thenReturn(if (topCutout != null) displayCutout else null)
        whenever(displayCutout.boundingRectTop).thenReturn(topCutout)

        return windowInsets
    }

    private fun mockInsetsProvider(
        insets: Pair<Int, Int> = 0 to 0,
        cornerCutout: Boolean = false,
    ) {
        whenever(insetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(insets.toAndroidPair())
        whenever(insetsProvider.currentRotationHasCornerCutout()).thenReturn(cornerCutout)
    }

    private fun createMockConstraintChanges(): ConstraintsChanges {
        return ConstraintsChanges(mock(), mock(), mock())
    }

    private fun XmlResourceParser.getResId(): Int {
        return Resources.getAttributeSetSourceResId(this)
    }

    private fun setUpMotionLayout(motionLayout: MotionLayout) {
        whenever(motionLayout.getConstraintSet(QQS_HEADER_CONSTRAINT)).thenReturn(qqsConstraints)
        whenever(motionLayout.getConstraintSet(QS_HEADER_CONSTRAINT)).thenReturn(qsConstraints)
        whenever(motionLayout.getConstraintSet(LARGE_SCREEN_HEADER_CONSTRAINT))
            .thenReturn(largeScreenConstraints)
    }

    private fun setUpDefaultInsets() {
        whenever(combinedShadeHeadersConstraintManager
            .edgesGuidelinesConstraints(anyInt(), anyInt(), anyInt(), anyInt())
        ).thenReturn(EMPTY_CHANGES)
        whenever(combinedShadeHeadersConstraintManager.emptyCutoutConstraints())
            .thenReturn(EMPTY_CHANGES)
        whenever(combinedShadeHeadersConstraintManager
            .centerCutoutConstraints(anyBoolean(), anyInt())
        ).thenReturn(EMPTY_CHANGES)
        whenever(combinedShadeHeadersConstraintManager
            .privacyChipVisibilityConstraints(anyBoolean())
        ).thenReturn(EMPTY_CHANGES)
        whenever(insetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(Pair(0, 0).toAndroidPair())
        whenever(insetsProvider.currentRotationHasCornerCutout()).thenReturn(false)
    }

    private fun<T, U> Pair<T, U>.toAndroidPair(): android.util.Pair<T, U> {
        return android.util.Pair(first, second)
    }
}
