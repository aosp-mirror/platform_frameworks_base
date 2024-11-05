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

import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.vibrator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
@SmallTest
class UnfoldHapticsPlayerTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val progressProvider = kosmos.fakeUnfoldTransitionProgressProvider
    private val vibrator = kosmos.vibrator
    private val transitionConfig = kosmos.unfoldTransitionConfig
    private val testFoldProvider = kosmos.foldProvider

    private lateinit var player: UnfoldHapticsPlayer

    @Before
    fun before() {
        transitionConfig.isHapticsEnabled = true
        player = UnfoldHapticsPlayer(progressProvider, testFoldProvider, transitionConfig,
            Runnable::run, vibrator)
    }

    @Test
    fun testUnfoldingTransitionFinishingEarly_playsHaptics() {
        testFoldProvider.onFoldUpdate(isFolded = true)
        testFoldProvider.onFoldUpdate(isFolded = false)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinishing()

        verify(vibrator).vibrate(any<VibrationEffect>(), any<VibrationAttributes>())
    }

    @Test
    fun testHapticsDisabled_unfoldingTransitionFinishing_doesNotPlayHaptics() {
        transitionConfig.isHapticsEnabled = false
        player = UnfoldHapticsPlayer(progressProvider, testFoldProvider, transitionConfig,
                Runnable::run, vibrator)

        testFoldProvider.onFoldUpdate(isFolded = true)
        testFoldProvider.onFoldUpdate(isFolded = false)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)
        progressProvider.onTransitionFinishing()

        verify(vibrator).vibrate(any<VibrationEffect>(), any<VibrationAttributes>())
    }

    @Test
    fun testUnfoldingTransitionFinishingLate_doesNotPlayHaptics() {
        testFoldProvider.onFoldUpdate(isFolded = true)
        testFoldProvider.onFoldUpdate(isFolded = false)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.99f)
        progressProvider.onTransitionFinishing()

        verify(vibrator, never()).vibrate(any<VibrationEffect>(), any<VibrationAttributes>())
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

        verify(vibrator, never()).vibrate(any<VibrationEffect>(), any<VibrationAttributes>())
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

        verify(vibrator).vibrate(any<VibrationEffect>(), any<VibrationAttributes>())
    }
}
