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
package com.android.systemui.unfold.updates

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.unfold.system.DeviceStateRepositoryImpl
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DeviceStateRepositoryTest : SysuiTestCase() {

    private val foldProvider = mock<FoldProvider>()
    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val foldStateRepository = DeviceStateRepositoryImpl(foldProvider) { r -> r.run() }

    @Test
    fun onHingeAngleUpdate_received() =
        testScope.runTest {
            val flowValue = collectLastValue(foldStateRepository.isFolded)
            val foldCallback = argumentCaptor<FoldProvider.FoldCallback>()

            verify(foldProvider).registerCallback(capture(foldCallback), any())

            foldCallback.value.onFoldUpdated(true)
            assertThat(flowValue()).isEqualTo(true)

            foldCallback.value.onFoldUpdated(false)
            assertThat(flowValue()).isEqualTo(false)
        }

    @Test
    fun onHingeAngleUpdate_unregisters() {
        testScope.runTest {
            val flowValue = collectLastValue(foldStateRepository.isFolded)

            verify(foldProvider).registerCallback(any(), any())
        }
        verify(foldProvider).unregisterCallback(any())
    }
}
