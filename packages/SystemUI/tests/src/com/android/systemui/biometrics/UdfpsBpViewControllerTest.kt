/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class UdfpsBpViewControllerTest : SysuiTestCase() {

    @JvmField @Rule var rule = MockitoJUnit.rule()

    @Mock lateinit var udfpsBpView: UdfpsBpView
    @Mock lateinit var statusBarStateController: StatusBarStateController
    @Mock lateinit var shadeExpansionStateManager: ShadeExpansionStateManager
    @Mock lateinit var systemUIDialogManager: SystemUIDialogManager
    @Mock lateinit var dumpManager: DumpManager

    private lateinit var udfpsBpViewController: UdfpsBpViewController

    @Before
    fun setup() {
        whenever(shadeExpansionStateManager.addExpansionListener(any()))
            .thenReturn(ShadeExpansionChangeEvent(0f, false, false, 0f))
        udfpsBpViewController =
            UdfpsBpViewController(
                udfpsBpView,
                statusBarStateController,
                shadeExpansionStateManager,
                systemUIDialogManager,
                dumpManager
            )
    }

    @Test
    fun testPauseAuthWhenNotificationShadeDragging() {
        udfpsBpViewController.onViewAttached()
        val shadeExpansionListener = withArgCaptor {
            verify(shadeExpansionStateManager).addExpansionListener(capture())
        }

        // When shade is tracking, should pause auth
        shadeExpansionListener.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 0f,
                expanded = false,
                tracking = true,
                dragDownPxAmount = 10f
            )
        )
        assert(udfpsBpViewController.shouldPauseAuth())

        // When shade is not tracking, don't pause auth even if expanded
        shadeExpansionListener.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 0f,
                expanded = true,
                tracking = false,
                dragDownPxAmount = 10f
            )
        )
        assertFalse(udfpsBpViewController.shouldPauseAuth())
    }
}
