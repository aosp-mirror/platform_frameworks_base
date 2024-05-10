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
import com.android.systemui.unfold.updates.FoldStateRepository.FoldUpdate
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
class FoldStateRepositoryTest : SysuiTestCase() {

    private val foldStateProvider = mock<FoldStateProvider>()
    private val foldUpdatesListener = argumentCaptor<FoldStateProvider.FoldUpdatesListener>()
    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val foldStateRepository = FoldStateRepositoryImpl(foldStateProvider)
    @Test
    fun onHingeAngleUpdate_received() =
        testScope.runTest {
            val flowValue = collectLastValue(foldStateRepository.hingeAngle)

            verify(foldStateProvider).addCallback(capture(foldUpdatesListener))
            foldUpdatesListener.value.onHingeAngleUpdate(42f)

            assertThat(flowValue()).isEqualTo(42f)
        }

    @Test
    fun onFoldUpdate_received() =
        testScope.runTest {
            val flowValue = collectLastValue(foldStateRepository.foldUpdate)

            verify(foldStateProvider).addCallback(capture(foldUpdatesListener))
            foldUpdatesListener.value.onFoldUpdate(FOLD_UPDATE_START_OPENING)

            assertThat(flowValue()).isEqualTo(FoldUpdate.START_OPENING)
        }

    @Test
    fun foldUpdates_mappedCorrectly() {
        mapOf(
                FOLD_UPDATE_START_OPENING to FoldUpdate.START_OPENING,
                FOLD_UPDATE_START_CLOSING to FoldUpdate.START_CLOSING,
                FOLD_UPDATE_FINISH_HALF_OPEN to FoldUpdate.FINISH_HALF_OPEN,
                FOLD_UPDATE_FINISH_FULL_OPEN to FoldUpdate.FINISH_FULL_OPEN,
                FOLD_UPDATE_FINISH_CLOSED to FoldUpdate.FINISH_CLOSED
            )
            .forEach { (id, expected) ->
                assertThat(FoldUpdate.fromFoldUpdateId(id)).isEqualTo(expected)
            }
    }
}
