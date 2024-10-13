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

import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_CLOSING
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_OPENING
import com.android.systemui.unfold.util.TestFoldStateProvider
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test class tests [PhysicsBasedUnfoldTransitionProgressProvider] in a more E2E
 * fashion, it uses real handler thread and timings, so it might be perceptible to more flakiness
 * compared to the other unit tests that do not perform real multithreaded interactions.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class PhysicsBasedUnfoldTransitionProgressProviderTest : SysuiTestCase() {

    private val foldStateProvider: TestFoldStateProvider = TestFoldStateProvider()
    private val listener = TestUnfoldProgressListener()
    private lateinit var progressProvider: UnfoldTransitionProgressProvider
    private val schedulerFactory =
        mock<UnfoldFrameCallbackScheduler.Factory>().apply {
            whenever(create()).then { UnfoldFrameCallbackScheduler() }
        }
    private val handlerThread = HandlerThread("UnfoldBg").apply { start() }
    private val bgHandler = Handler(handlerThread.looper)

    @Before
    fun setUp() {
        progressProvider =
            PhysicsBasedUnfoldTransitionProgressProvider(
                context,
                schedulerFactory,
                foldStateProvider = foldStateProvider,
                progressHandler = bgHandler
            )
        progressProvider.addCallback(listener)
    }

    @After
    fun after() {
        handlerThread.quit()
    }

    @Test
    fun testUnfold_emitsIncreasingTransitionEvents() {
        runOnProgressThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendUnfoldedScreenAvailable() }
        )
        sendHingeAngleAndEnsureAnimationUpdate(90f, 120f, 180f)
        runOnProgressThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) }
        )

        with(listener.ensureTransitionFinished()) {
            assertIncreasingProgress()
            assertFinishedWithUnfold()
        }
    }

    @Test
    fun testUnfold_emitsFinishingEvent() {
        runOnProgressThreadWithInterval(
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
    fun testUnfold_screenAvailableOnlyAfterFullUnfold_finishesWithUnfoldEvent() {
        runOnProgressThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            { foldStateProvider.sendHingeAngleUpdate(180f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) },
            { foldStateProvider.sendUnfoldedScreenAvailable() },
        )

        with(listener.ensureTransitionFinished()) {
            assertFinishedWithUnfold()
        }
    }

    @Test
    fun testFold_emitsDecreasingTransitionEvents() {
        runOnProgressThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_CLOSING) },
        )
        sendHingeAngleAndEnsureAnimationUpdate(170f, 90f, 10f)
        runOnProgressThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_CLOSED) },
        )

        with(listener.ensureTransitionFinished()) {
            assertDecreasingProgress()
            assertFinishedWithFold()
        }
    }

    @Test
    fun testUnfoldAndStopUnfolding_finishesTheUnfoldTransition() {
        runOnProgressThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendUnfoldedScreenAvailable() })
        sendHingeAngleAndEnsureAnimationUpdate(10f, 50f, 90f)
        runOnProgressThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN) },
        )

        with(listener.ensureTransitionFinished()) {
            assertIncreasingProgress()
            assertFinishedWithUnfold()
        }
    }

    @Test
    fun testFoldImmediatelyAfterUnfold_runsFoldAnimation() {
        runOnProgressThreadWithInterval(
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

    private fun sendHingeAngleAndEnsureAnimationUpdate(vararg angles: Float) {
        angles.forEach { angle ->
            listener.waitForProgressChangeAfter {
                bgHandler.post {
                    foldStateProvider.sendHingeAngleUpdate(angle)
                }
            }
        }
    }

    private fun runOnProgressThreadWithInterval(
        vararg blocks: () -> Unit,
        intervalMillis: Long = 60,
    ) {
        blocks.forEach {
            bgHandler.post(it)
            Thread.sleep(intervalMillis)
        }
    }
}
