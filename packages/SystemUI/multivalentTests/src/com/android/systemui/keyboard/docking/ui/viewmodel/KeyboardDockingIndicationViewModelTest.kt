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

package com.android.systemui.keyboard.docking.ui.viewmodel

import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.keyboard.docking.domain.interactor.KeyboardDockingIndicationInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.spy
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardDockingIndicationViewModelTest : SysuiTestCase() {
    private val testScope = TestScope(StandardTestDispatcher())

    private lateinit var keyboardRepository: FakeKeyboardRepository
    private lateinit var configurationRepository: FakeConfigurationRepository
    private lateinit var underTest: KeyboardDockingIndicationViewModel
    private lateinit var windowManager: WindowManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        keyboardRepository = FakeKeyboardRepository()
        configurationRepository = FakeConfigurationRepository()
        windowManager = spy(context.getSystemService(WindowManager::class.java)!!)

        val keyboardDockingIndicationInteractor =
            KeyboardDockingIndicationInteractor(keyboardRepository)
        val configurationInteractor = ConfigurationInteractor(configurationRepository)

        underTest =
            KeyboardDockingIndicationViewModel(
                windowManager,
                context,
                keyboardDockingIndicationInteractor,
                configurationInteractor,
                testScope.backgroundScope
            )
    }

    @Test
    fun onConfigurationChanged_createsNewConfig() {
        val oldBounds = Rect(0, 0, 10, 10)
        val newBounds = Rect(10, 10, 20, 20)
        val inset = WindowInsets(Rect(1, 1, 1, 1))
        val density = 1f

        doReturn(WindowMetrics(oldBounds, inset, density))
            .whenever(windowManager)
            .currentWindowMetrics

        val firstGlow = underTest.edgeGlow.value

        testScope.runTest {
            configurationRepository.onAnyConfigurationChange()
            // Ensure there's some change in the config so that flow emits the new value.
            doReturn(WindowMetrics(newBounds, inset, density))
                .whenever(windowManager)
                .currentWindowMetrics

            val secondGlow = underTest.edgeGlow.value

            assertThat(firstGlow.hashCode()).isNotEqualTo(secondGlow.hashCode())
        }
    }
}
