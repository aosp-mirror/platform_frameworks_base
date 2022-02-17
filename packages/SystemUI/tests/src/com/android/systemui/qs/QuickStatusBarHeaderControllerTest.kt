/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs

import android.content.Context
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.colorextraction.SysuiColorExtractor
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.qs.carrier.QSCarrierGroup
import com.android.systemui.qs.carrier.QSCarrierGroupController
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.VariableDateView
import com.android.systemui.statusbar.policy.VariableDateViewController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QuickStatusBarHeaderControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var view: QuickStatusBarHeader
    @Mock
    private lateinit var privacyIconsController: HeaderPrivacyIconsController
    @Mock
    private lateinit var statusBarIconController: StatusBarIconController
    @Mock
    private lateinit var demoModeController: DemoModeController
    @Mock
    private lateinit var quickQSPanelController: QuickQSPanelController
    @Mock(answer = Answers.RETURNS_SELF)
    private lateinit var qsCarrierGroupControllerBuilder: QSCarrierGroupController.Builder
    @Mock
    private lateinit var qsCarrierGroupController: QSCarrierGroupController
    @Mock
    private lateinit var colorExtractor: SysuiColorExtractor
    @Mock
    private lateinit var iconContainer: StatusIconContainer
    @Mock
    private lateinit var qsCarrierGroup: QSCarrierGroup
    @Mock
    private lateinit var variableDateViewControllerFactory: VariableDateViewController.Factory
    @Mock
    private lateinit var variableDateViewController: VariableDateViewController
    @Mock
    private lateinit var batteryMeterViewController: BatteryMeterViewController
    @Mock
    private lateinit var clock: Clock
    @Mock
    private lateinit var variableDateView: VariableDateView
    @Mock
    private lateinit var mockView: View
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var context: Context
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Mock
    private lateinit var insetsProvider: StatusBarContentInsetsProvider

    private val qsExpansionPathInterpolator = QSExpansionPathInterpolator()

    private lateinit var controller: QuickStatusBarHeaderController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        stubViews()
        `when`(iconContainer.context).thenReturn(context)
        `when`(qsCarrierGroupControllerBuilder.build()).thenReturn(qsCarrierGroupController)
        `when`(variableDateViewControllerFactory.create(any()))
                .thenReturn(variableDateViewController)
        `when`(view.resources).thenReturn(mContext.resources)
        `when`(view.isAttachedToWindow).thenReturn(true)
        `when`(view.context).thenReturn(context)

        controller = QuickStatusBarHeaderController(
                view,
                privacyIconsController,
                statusBarIconController,
                demoModeController,
                quickQSPanelController,
                qsCarrierGroupControllerBuilder,
                colorExtractor,
                qsExpansionPathInterpolator,
                featureFlags,
                variableDateViewControllerFactory,
                batteryMeterViewController,
                insetsProvider
        )
    }

    @After
    fun tearDown() {
        controller.onViewDetached()
    }

    @Test
    fun testClockNotClickable() {
        assertThat(clock.isClickable).isFalse()
    }

    @Test
    fun testSingleCarrierListenerAttachedOnInit() {
        controller.init()

        verify(qsCarrierGroupController).setOnSingleCarrierChangedListener(any())
    }

    @Test
    fun testSingleCarrierSetOnViewOnInit_false() {
        `when`(qsCarrierGroupController.isSingleCarrier).thenReturn(false)
        controller.init()

        verify(view).setIsSingleCarrier(false)
    }

    @Test
    fun testSingleCarrierSetOnViewOnInit_true() {
        `when`(qsCarrierGroupController.isSingleCarrier).thenReturn(true)
        controller.init()

        verify(view).setIsSingleCarrier(true)
    }

    @Test
    fun testRSSISlot_notCombined() {
        `when`(featureFlags.isEnabled(Flags.COMBINED_STATUS_BAR_SIGNAL_ICONS)).thenReturn(false)
        controller.init()

        val captor = argumentCaptor<List<String>>()
        verify(view).onAttach(any(), any(), capture(captor), any(), anyBoolean())

        assertThat(captor.value).containsExactly(
            mContext.getString(com.android.internal.R.string.status_bar_mobile)
        )
    }

    @Test
    fun testRSSISlot_combined() {
        `when`(featureFlags.isEnabled(Flags.COMBINED_STATUS_BAR_SIGNAL_ICONS)).thenReturn(true)
        controller.init()

        val captor = argumentCaptor<List<String>>()
        verify(view).onAttach(any(), any(), capture(captor), any(), anyBoolean())

        assertThat(captor.value).containsExactly(
            mContext.getString(com.android.internal.R.string.status_bar_no_calling),
            mContext.getString(com.android.internal.R.string.status_bar_call_strength)
        )
    }

    @Test
    fun testSingleCarrierCallback() {
        controller.init()
        reset(view)

        val captor = argumentCaptor<QSCarrierGroupController.OnSingleCarrierChangedListener>()
        verify(qsCarrierGroupController).setOnSingleCarrierChangedListener(capture(captor))

        captor.value.onSingleCarrierChanged(true)
        verify(view).setIsSingleCarrier(true)

        captor.value.onSingleCarrierChanged(false)
        verify(view).setIsSingleCarrier(false)
    }

    private fun stubViews() {
        `when`(view.findViewById<View>(anyInt())).thenReturn(mockView)
        `when`(view.findViewById<QSCarrierGroup>(R.id.carrier_group)).thenReturn(qsCarrierGroup)
        `when`(view.findViewById<StatusIconContainer>(R.id.statusIcons)).thenReturn(iconContainer)
        `when`(view.findViewById<Clock>(R.id.clock)).thenReturn(clock)
        `when`(view.requireViewById<VariableDateView>(R.id.date)).thenReturn(variableDateView)
        `when`(view.requireViewById<VariableDateView>(R.id.date_clock)).thenReturn(variableDateView)
    }
}
