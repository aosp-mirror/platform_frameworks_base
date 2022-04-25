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

package com.android.systemui.biometrics

import android.app.Instrumentation
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.StatusBarStateControllerImpl
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager
import com.android.systemui.util.mockito.any
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class UdfpsBpViewControllerTest : SysuiTestCase() {

    @JvmField @Rule var rule = MockitoJUnit.rule()

    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var systemUIDialogManager: SystemUIDialogManager
    @Mock lateinit var broadcastSender: BroadcastSender
    @Mock lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock lateinit var panelExpansionStateManager: PanelExpansionStateManager

    private lateinit var instrumentation: Instrumentation
    private lateinit var uiEventLogger: UiEventLoggerFake
    private lateinit var udfpsBpView: UdfpsBpView
    private lateinit var statusBarStateController: StatusBarStateControllerImpl
    private lateinit var udfpsBpViewController: UdfpsBpViewController

    @Before
    fun setup() {
        instrumentation = getInstrumentation()
        instrumentation.runOnMainSync { createUdfpsView() }
        instrumentation.waitForIdleSync()

        uiEventLogger = UiEventLoggerFake()
        statusBarStateController =
            StatusBarStateControllerImpl(uiEventLogger, dumpManager, interactionJankMonitor)
        udfpsBpViewController = UdfpsBpViewController(
            udfpsBpView,
            statusBarStateController,
            panelExpansionStateManager,
            systemUIDialogManager,
            broadcastSender,
            dumpManager)
        udfpsBpViewController.init()
    }

    @After
    fun tearDown() {
        if (udfpsBpViewController.isAttachedToWindow) {
            instrumentation.runOnMainSync { ViewUtils.detachView(udfpsBpView) }
            instrumentation.waitForIdleSync()
        }
    }

    private fun createUdfpsView() {
        context.setTheme(R.style.Theme_AppCompat)
        context.orCreateTestableResources.addOverride(
            com.android.internal.R.integer.config_udfps_illumination_transition_ms, 0)
        udfpsBpView = UdfpsBpView(context, null)
    }

    @Test
    fun addExpansionListener() {
        instrumentation.runOnMainSync { ViewUtils.attachView(udfpsBpView) }
        instrumentation.waitForIdleSync()

        // Both UdfpsBpViewController & UdfpsAnimationViewController add listener
        verify(panelExpansionStateManager, times(2)).addExpansionListener(any())
    }

    @Test
    fun removeExpansionListener() {
        instrumentation.runOnMainSync { ViewUtils.attachView(udfpsBpView) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { ViewUtils.detachView(udfpsBpView) }
        instrumentation.waitForIdleSync()

        // Both UdfpsBpViewController & UdfpsAnimationViewController remove listener
        verify(panelExpansionStateManager, times(2)).removeExpansionListener(any())
    }
}
