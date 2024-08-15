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

package com.android.systemui.keyboard.stickykeys.ui

import android.app.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyboard.data.repository.FakeStickyKeysRepository
import com.android.systemui.keyboard.data.repository.keyboardRepository
import com.android.systemui.keyboard.stickykeys.StickyKeysLogger
import com.android.systemui.keyboard.stickykeys.shared.model.Locked
import com.android.systemui.keyboard.stickykeys.shared.model.ModifierKey.SHIFT
import com.android.systemui.keyboard.stickykeys.ui.viewmodel.StickyKeysIndicatorViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class StickyKeysIndicatorCoordinatorTest : SysuiTestCase() {

    private lateinit var coordinator: StickyKeysIndicatorCoordinator
    private val testScope = TestScope(StandardTestDispatcher())
    private val stickyKeysRepository = FakeStickyKeysRepository()
    private val dialog = mock<Dialog>()

    @Before
    fun setup() {
        val dialogFactory = mock<StickyKeyDialogFactory>()
        whenever(dialogFactory.create(any())).thenReturn(dialog)
        val keyboardRepository = Kosmos().keyboardRepository
        val viewModel =
            StickyKeysIndicatorViewModel(
                stickyKeysRepository,
                keyboardRepository,
                testScope.backgroundScope
            )
        coordinator =
            StickyKeysIndicatorCoordinator(
                testScope.backgroundScope,
                dialogFactory,
                viewModel,
                mock<StickyKeysLogger>()
            )
        coordinator.startListening()
        keyboardRepository.setIsAnyKeyboardConnected(true)
    }

    @Test
    fun dialogIsShownWhenStickyKeysAreEmitted() {
        testScope.run {
            verifyZeroInteractions(dialog)

            stickyKeysRepository.setStickyKeys(linkedMapOf(SHIFT to Locked(true)))
            runCurrent()

            verify(dialog).show()
        }
    }

    @Test
    fun dialogDisappearsWhenStickyKeysAreEmpty() {
        testScope.run {
            verifyZeroInteractions(dialog)

            stickyKeysRepository.setStickyKeys(linkedMapOf(SHIFT to Locked(true)))
            runCurrent()
            stickyKeysRepository.setStickyKeys(linkedMapOf())
            runCurrent()

            verify(dialog).dismiss()
        }
    }
}
