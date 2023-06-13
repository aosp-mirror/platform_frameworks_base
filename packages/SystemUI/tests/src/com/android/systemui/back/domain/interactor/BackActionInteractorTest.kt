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

package com.android.systemui.back.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.QuickSettingsController
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
class BackActionInteractorTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var shadeController: ShadeController
    @Mock private lateinit var qsController: QuickSettingsController
    @Mock private lateinit var shadeViewController: ShadeViewController

    private lateinit var backActionInteractor: BackActionInteractor

    @Before
    fun setup() {
        backActionInteractor =
            BackActionInteractor(
                statusBarStateController,
                statusBarKeyguardViewManager,
                shadeController,
            )
        backActionInteractor.setup(qsController, shadeViewController)
    }

    @Test
    fun testOnBackRequested_keyguardCanHandleBackPressed() {
        whenever(statusBarKeyguardViewManager.canHandleBackPressed()).thenReturn(true)

        val result = backActionInteractor.onBackRequested()

        assertTrue(result)
        verify(statusBarKeyguardViewManager, atLeastOnce()).onBackPressed()
    }

    @Test
    fun testOnBackRequested_quickSettingsIsCustomizing() {
        whenever(qsController.isCustomizing).thenReturn(true)

        val result = backActionInteractor.onBackRequested()

        assertTrue(result)
        verify(qsController, atLeastOnce()).closeQsCustomizer()
        verify(statusBarKeyguardViewManager, never()).onBackPressed()
    }

    @Test
    fun testOnBackRequested_quickSettingsExpanded() {
        whenever(qsController.expanded).thenReturn(true)

        val result = backActionInteractor.onBackRequested()

        assertTrue(result)
        verify(shadeViewController, atLeastOnce()).animateCollapseQs(anyBoolean())
        verify(statusBarKeyguardViewManager, never()).onBackPressed()
    }

    @Test
    fun testOnBackRequested_closeUserSwitcherIfOpen() {
        whenever(shadeViewController.closeUserSwitcherIfOpen()).thenReturn(true)

        val result = backActionInteractor.onBackRequested()

        assertTrue(result)
        verify(statusBarKeyguardViewManager, never()).onBackPressed()
        verify(shadeViewController, never()).animateCollapseQs(anyBoolean())
    }

    @Test
    fun testOnBackRequested_returnsFalse() {
        // make shouldBackBeHandled return false
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)

        val result = backActionInteractor.onBackRequested()

        assertFalse(result)
        verify(statusBarKeyguardViewManager, never()).onBackPressed()
        verify(shadeViewController, never()).animateCollapseQs(anyBoolean())
    }
}
