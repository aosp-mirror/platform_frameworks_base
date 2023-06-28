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
package com.android.systemui.unfold

import android.os.VibrationEffect
import android.os.Vibrator
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.updates.FoldProvider
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class UnfoldHapticsPlayerTest : SysuiTestCase() {

    private val progressProvider = TestUnfoldTransitionProvider()
    private val vibrator: Vibrator = mock()
    private val testFoldProvider = TestFoldProvider()

    private lateinit var player: UnfoldHapticsPlayer

    @Before
    fun before() {
        player = UnfoldHapticsPlayer(progressProvider, testFoldProvider, Runnable::run, vibrator)
    }

    @Test
    fun testUnfoldingTransitionFinishingEarly_playsHaptics() {
        testFoldProvider.onFoldUpdate(isFolded = true)
        testFoldProvider.onFoldUpdate(isFolded = false)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinishing()

        verify(vibrator).vibrate(any<VibrationEffect>())
    }

    @Test
    fun testUnfoldingTransitionFinishingLate_doesNotPlayHaptics() {
        testFoldProvider.onFoldUpdate(isFolded = true)
        testFoldProvider.onFoldUpdate(isFolded = false)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.99f)
        progressProvider.onTransitionFinishing()

        verify(vibrator, never()).vibrate(any<VibrationEffect>())
    }

    @Test
    fun testFoldingAfterUnfolding_doesNotPlayHaptics() {
        // Unfold
        testFoldProvider.onFoldUpdate(isFolded = true)
        testFoldProvider.onFoldUpdate(isFolded = false)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinishing()
        progressProvider.onTransitionFinished()
        clearInvocations(vibrator)

        // Fold
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinished()
        testFoldProvider.onFoldUpdate(isFolded = true)

        verify(vibrator, never()).vibrate(any<VibrationEffect>())
    }

    @Test
    fun testUnfoldingAfterFoldingAndUnfolding_playsHaptics() {
        // Unfold
        testFoldProvider.onFoldUpdate(isFolded = true)
        testFoldProvider.onFoldUpdate(isFolded = false)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinishing()
        progressProvider.onTransitionFinished()

        // Fold
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinished()
        testFoldProvider.onFoldUpdate(isFolded = true)
        clearInvocations(vibrator)

        // Unfold again
        testFoldProvider.onFoldUpdate(isFolded = true)
        testFoldProvider.onFoldUpdate(isFolded = false)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinishing()
        progressProvider.onTransitionFinished()

        verify(vibrator).vibrate(any<VibrationEffect>())
    }

    private class TestFoldProvider : FoldProvider {
        private val listeners = arrayListOf<FoldProvider.FoldCallback>()

        override fun registerCallback(callback: FoldProvider.FoldCallback, executor: Executor) {
            listeners += callback
        }

        override fun unregisterCallback(callback: FoldProvider.FoldCallback) {
            listeners -= callback
        }

        fun onFoldUpdate(isFolded: Boolean) {
            listeners.forEach { it.onFoldUpdated(isFolded) }
        }
    }
}
