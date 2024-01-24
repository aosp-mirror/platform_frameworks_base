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

package com.android.systemui.keyboard.stickykeys.ui.viewmodel

import android.hardware.input.InputManager
import android.hardware.input.StickyModifierState
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.keyboard.stickykeys.StickyKeysLogger
import com.android.systemui.keyboard.stickykeys.data.repository.StickyKeysRepositoryImpl
import com.android.systemui.keyboard.stickykeys.shared.model.Locked
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.ALT
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.ALT_GR
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.CTRL
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.META
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.SHIFT
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class StickyKeysIndicatorViewModelTest : SysuiTestCase() {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private lateinit var viewModel: StickyKeysIndicatorViewModel
    private val inputManager = mock<InputManager>()
    private val keyboardRepository = FakeKeyboardRepository()
    private val captor =
        ArgumentCaptor.forClass(InputManager.StickyModifierStateListener::class.java)

    @Before
    fun setup() {
        val stickyKeysRepository = StickyKeysRepositoryImpl(
            inputManager,
            dispatcher,
            mock<StickyKeysLogger>()
        )
        viewModel =
            StickyKeysIndicatorViewModel(
                stickyKeysRepository = stickyKeysRepository,
                keyboardRepository = keyboardRepository,
                applicationScope = testScope.backgroundScope,
            )
    }

    @Test
    fun startsListeningToStickyKeysOnlyWhenKeyboardIsConnected() {
        testScope.runTest {
            collectLastValue(viewModel.indicatorContent)
            runCurrent()
            verifyZeroInteractions(inputManager)

            keyboardRepository.setIsAnyKeyboardConnected(true)
            runCurrent()

            verify(inputManager)
                .registerStickyModifierStateListener(
                    any(),
                    any(InputManager.StickyModifierStateListener::class.java)
                )
        }
    }

    @Test
    fun stopsListeningToStickyKeysWhenKeyboardDisconnects() {
        testScope.runTest {
            collectLastValue(viewModel.indicatorContent)
            keyboardRepository.setIsAnyKeyboardConnected(true)
            runCurrent()

            keyboardRepository.setIsAnyKeyboardConnected(false)
            runCurrent()

            verify(inputManager).unregisterStickyModifierStateListener(any())
        }
    }

    @Test
    fun emitsStickyKeysListWhenStickyKeyIsPressed() {
        testScope.runTest {
            val stickyKeys by collectLastValue(viewModel.indicatorContent)
            keyboardRepository.setIsAnyKeyboardConnected(true)

            setStickyKeys(mapOf(ALT to false))

            assertThat(stickyKeys).isEqualTo(mapOf(ALT to Locked(false)))
        }
    }

    @Test
    fun emitsEmptyListWhenNoStickyKeysAreActive() {
        testScope.runTest {
            val stickyKeys by collectLastValue(viewModel.indicatorContent)
            keyboardRepository.setIsAnyKeyboardConnected(true)

            setStickyKeys(emptyMap())

            assertThat(stickyKeys).isEqualTo(emptyMap<ModifierKey, Locked>())
        }
    }

    @Test
    fun passesAllStickyKeysToDialog() {
        testScope.runTest {
            val stickyKeys by collectLastValue(viewModel.indicatorContent)
            keyboardRepository.setIsAnyKeyboardConnected(true)

            setStickyKeys(mapOf(
                ALT to false,
                META to false,
                SHIFT to false))

            assertThat(stickyKeys).isEqualTo(mapOf(
                ALT to Locked(false),
                META to Locked(false),
                SHIFT to Locked(false),
            ))
        }
    }

    @Test
    fun showsOnlyLockedStateIfKeyIsStickyAndLocked() {
        testScope.runTest {
            val stickyKeys by collectLastValue(viewModel.indicatorContent)
            keyboardRepository.setIsAnyKeyboardConnected(true)

            setStickyKeys(mapOf(
                ALT to false,
                ALT to true))

            assertThat(stickyKeys).isEqualTo(mapOf(ALT to Locked(true)))
        }
    }

    @Test
    fun doesNotChangeOrderOfKeysIfTheyBecomeLocked() {
        testScope.runTest {
            val stickyKeys by collectLastValue(viewModel.indicatorContent)
            keyboardRepository.setIsAnyKeyboardConnected(true)

            setStickyKeys(mapOf(
                META to false,
                SHIFT to false, // shift is sticky but not locked
                CTRL to false))
            val previousShiftIndex = stickyKeys?.toList()?.indexOf(SHIFT to Locked(false))

            setStickyKeys(mapOf(
                SHIFT to false,
                SHIFT to true, // shift is now locked
                META to false,
                CTRL to false))
            assertThat(stickyKeys?.toList()?.indexOf(SHIFT to Locked(true)))
                .isEqualTo(previousShiftIndex)
        }
    }

    private fun TestScope.setStickyKeys(keys: Map<ModifierKey, Boolean>) {
        runCurrent()
        verify(inputManager).registerStickyModifierStateListener(any(), captor.capture())
        captor.value.onStickyModifierStateChanged(TestStickyModifierState(keys))
        runCurrent()
    }

    private class TestStickyModifierState(private val keys: Map<ModifierKey, Boolean>) :
        StickyModifierState() {

        private fun isOn(key: ModifierKey) = keys.any { it.key == key && !it.value }
        private fun isLocked(key: ModifierKey) = keys.any { it.key == key && it.value }

        override fun isAltGrModifierLocked() = isLocked(ALT_GR)
        override fun isAltGrModifierOn() = isOn(ALT_GR)
        override fun isAltModifierLocked() = isLocked(ALT)
        override fun isAltModifierOn() = isOn(ALT)
        override fun isCtrlModifierLocked() = isLocked(CTRL)
        override fun isCtrlModifierOn() = isOn(CTRL)
        override fun isMetaModifierLocked() = isLocked(META)
        override fun isMetaModifierOn() = isOn(META)
        override fun isShiftModifierLocked() = isLocked(SHIFT)
        override fun isShiftModifierOn() = isOn(SHIFT)
    }
}
