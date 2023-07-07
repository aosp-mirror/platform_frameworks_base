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
package com.android.systemui.unfold.progress

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_CLOSING
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_OPENING
import com.android.systemui.unfold.util.TestFoldStateProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class PhysicsBasedUnfoldTransitionProgressProviderTest : SysuiTestCase() {

    private val foldStateProvider: TestFoldStateProvider = TestFoldStateProvider()
    private val listener = TestUnfoldProgressListener()
    private lateinit var progressProvider: UnfoldTransitionProgressProvider

    @Before
    fun setUp() {
        progressProvider = PhysicsBasedUnfoldTransitionProgressProvider(context, foldStateProvider)
        progressProvider.addCallback(listener)
    }

    @Test
    fun testUnfold_emitsIncreasingTransitionEvents() {
        runOnMainThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendUnfoldedScreenAvailable() },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            { foldStateProvider.sendHingeAngleUpdate(180f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) },
        )

        with(listener.ensureTransitionFinished()) {
            assertIncreasingProgress()
            assertFinishedWithUnfold()
        }
    }

    @Test
    fun testUnfold_emitsFinishingEvent() {
        runOnMainThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendUnfoldedScreenAvailable() },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            { foldStateProvider.sendHingeAngleUpdate(180f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) },
        )

        with(listener.ensureTransitionFinished()) { assertHasSingleFinishingEvent() }
    }

    @Test
    fun testUnfold_screenAvailableOnlyAfterFullUnfold_emitsIncreasingTransitionEvents() {
        runOnMainThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            { foldStateProvider.sendHingeAngleUpdate(180f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) },
            { foldStateProvider.sendUnfoldedScreenAvailable() },
        )

        with(listener.ensureTransitionFinished()) {
            assertIncreasingProgress()
            assertFinishedWithUnfold()
        }
    }

    @Test
    fun testFold_emitsDecreasingTransitionEvents() {
        runOnMainThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_CLOSING) },
            { foldStateProvider.sendHingeAngleUpdate(170f) },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_CLOSED) },
        )

        with(listener.ensureTransitionFinished()) {
            assertDecreasingProgress()
            assertFinishedWithFold()
        }
    }

    @Test
    fun testUnfoldAndStopUnfolding_finishesTheUnfoldTransition() {
        runOnMainThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendUnfoldedScreenAvailable() },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN) },
        )

        with(listener.ensureTransitionFinished()) {
            assertIncreasingProgress()
            assertFinishedWithUnfold()
        }
    }

    @Test
    fun testFoldImmediatelyAfterUnfold_runsFoldAnimation() {
        runOnMainThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendUnfoldedScreenAvailable() },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            {
                foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)
                // Start closing immediately after we opened, before the animation ended
                foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_CLOSING)
            },
            { foldStateProvider.sendHingeAngleUpdate(60f) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_CLOSED) },
        )

        with(listener.ensureTransitionFinished()) { assertHasFoldAnimationAtTheEnd() }
    }

    private fun runOnMainThreadWithInterval(vararg blocks: () -> Unit, intervalMillis: Long = 60) {
        blocks.forEach {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { it() }
            Thread.sleep(intervalMillis)
        }
    }
}
