/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.navigation.domain.interactor

import android.view.WindowManagerPolicyConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.navigationbar.NavigationModeController.ModeChangedListener
import com.android.systemui.navigationbar.navigationModeController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class NavigationInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val navigationModeControllerMock = kosmos.navigationModeController

    private val underTest = kosmos.navigationInteractor

    private var currentMode = WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON
    private val modeChangedListeners = mutableListOf<ModeChangedListener>()

    @Before
    fun setUp() {
        whenever(navigationModeControllerMock.addListener(any())).thenAnswer { invocation ->
            val listener = invocation.arguments[0] as ModeChangedListener
            modeChangedListeners.add(listener)
            currentMode
        }
    }

    @Test
    fun isGesturalMode() =
        testScope.runTest {
            val isGesturalMode by collectLastValue(underTest.isGesturalMode)
            assertThat(isGesturalMode).isFalse()

            currentMode = WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL
            notifyModeChangedListeners()
            assertThat(isGesturalMode).isTrue()

            currentMode = WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON
            notifyModeChangedListeners()
            assertThat(isGesturalMode).isFalse()
        }

    private fun notifyModeChangedListeners() {
        modeChangedListeners.forEach { listener -> listener.onNavigationModeChanged(currentMode) }
    }
}
