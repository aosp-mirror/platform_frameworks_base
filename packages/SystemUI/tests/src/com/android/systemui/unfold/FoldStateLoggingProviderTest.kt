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

package com.android.systemui.unfold

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.FoldStateLoggingProvider.FoldStateLoggingListener
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_CLOSING
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_OPENING
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdate
import com.android.systemui.unfold.util.TestFoldStateProvider
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class FoldStateLoggingProviderTest : SysuiTestCase() {

    private val testFoldStateProvider = TestFoldStateProvider()
    private val fakeClock = FakeSystemClock()

    private lateinit var foldStateLoggingProvider: FoldStateLoggingProvider

    private val foldLoggingUpdates: MutableList<FoldStateChange> = arrayListOf()

    private val foldStateLoggingListener =
        object : FoldStateLoggingListener {
            override fun onFoldUpdate(foldStateUpdate: FoldStateChange) {
                foldLoggingUpdates.add(foldStateUpdate)
            }
        }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        foldStateLoggingProvider =
            FoldStateLoggingProviderImpl(testFoldStateProvider, fakeClock).apply {
                addCallback(foldStateLoggingListener)
                init()
            }
    }

    @Test
    fun onFoldUpdate_noPreviousOne_finishHalfOpen_nothingReported() {
        sendFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN)

        assertThat(foldLoggingUpdates).isEmpty()
    }

    @Test
    fun onFoldUpdate_noPreviousOne_finishFullOpen_nothingReported() {
        sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)

        assertThat(foldLoggingUpdates).isEmpty()
    }

    @Test
    fun onFoldUpdate_noPreviousOne_finishClosed_nothingReported() {
        sendFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)

        assertThat(foldLoggingUpdates).isEmpty()
    }

    @Test
    fun onFoldUpdate_startOpening_fullOpen_changeReported() {
        val dtTime = 10L

        sendFoldUpdate(FOLD_UPDATE_START_OPENING)
        fakeClock.advanceTime(dtTime)
        sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)

        assertThat(foldLoggingUpdates)
            .containsExactly(FoldStateChange(FULLY_CLOSED, FULLY_OPENED, dtTime))
    }

    @Test
    fun onFoldUpdate_startClosingThenFinishClosed_noInitialState_nothingReported() {
        val dtTime = 10L

        sendFoldUpdate(FOLD_UPDATE_START_CLOSING)
        fakeClock.advanceTime(dtTime)
        sendFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)

        assertThat(foldLoggingUpdates).isEmpty()
    }

    @Test
    fun onFoldUpdate_startClosingThenFinishClosed_initiallyOpened_changeReported() {
        val dtTime = 10L
        sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)

        sendFoldUpdate(FOLD_UPDATE_START_CLOSING)
        fakeClock.advanceTime(dtTime)
        sendFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)

        assertThat(foldLoggingUpdates)
            .containsExactly(FoldStateChange(FULLY_OPENED, FULLY_CLOSED, dtTime))
    }

    @Test
    fun onFoldUpdate_startOpeningThenHalf_initiallyClosed_changeReported() {
        val dtTime = 10L
        sendFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)

        sendFoldUpdate(FOLD_UPDATE_START_OPENING)
        fakeClock.advanceTime(dtTime)
        sendFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN)

        assertThat(foldLoggingUpdates)
            .containsExactly(FoldStateChange(FULLY_CLOSED, HALF_OPENED, dtTime))
    }

    @Test
    fun onFoldUpdate_startClosingThenHalf_initiallyOpened_changeReported() {
        val dtTime = 10L
        sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)

        sendFoldUpdate(FOLD_UPDATE_START_CLOSING)
        fakeClock.advanceTime(dtTime)
        sendFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN)

        assertThat(foldLoggingUpdates)
            .containsExactly(FoldStateChange(FULLY_OPENED, HALF_OPENED, dtTime))
    }

    @Test
    fun onFoldUpdate_foldThenUnfold_multipleReported() {
        val foldTime = 24L
        val unfoldTime = 42L
        val waitingTime = 424L
        sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)

        // Fold
        sendFoldUpdate(FOLD_UPDATE_START_CLOSING)
        fakeClock.advanceTime(foldTime)
        sendFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)
        fakeClock.advanceTime(waitingTime)
        // unfold
        sendFoldUpdate(FOLD_UPDATE_START_OPENING)
        fakeClock.advanceTime(unfoldTime)
        sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)

        assertThat(foldLoggingUpdates)
            .containsExactly(
                FoldStateChange(FULLY_OPENED, FULLY_CLOSED, foldTime),
                FoldStateChange(FULLY_CLOSED, FULLY_OPENED, unfoldTime))
    }

    @Test
    fun uninit_removesCallback() {
        foldStateLoggingProvider.uninit()

        assertThat(testFoldStateProvider.hasListeners).isFalse()
    }

    private fun sendFoldUpdate(@FoldUpdate foldUpdate: Int) {
        testFoldStateProvider.sendFoldUpdate(foldUpdate)
    }
}
