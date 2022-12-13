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
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_OPENING
import com.android.systemui.unfold.updates.FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_CLOSING
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.util.TestFoldStateProvider
import com.android.systemui.util.leak.ReferenceTestUtils.waitForCondition
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
        progressProvider = PhysicsBasedUnfoldTransitionProgressProvider(
            foldStateProvider
        )
        progressProvider.addCallback(listener)
    }

    @Test
    fun testUnfold_emitsIncreasingTransitionEvents() {
        runOnMainThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE) },
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
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE) },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            { foldStateProvider.sendHingeAngleUpdate(180f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) },
        )

        with(listener.ensureTransitionFinished()) {
            assertHasSingleFinishingEvent()
        }
    }

    @Test
    fun testUnfold_screenAvailableOnlyAfterFullUnfold_emitsIncreasingTransitionEvents() {
        runOnMainThreadWithInterval(
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_START_OPENING) },
            { foldStateProvider.sendHingeAngleUpdate(10f) },
            { foldStateProvider.sendHingeAngleUpdate(90f) },
            { foldStateProvider.sendHingeAngleUpdate(180f) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN) },
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE) },
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
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE) },
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
            { foldStateProvider.sendFoldUpdate(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE) },
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

        with(listener.ensureTransitionFinished()) {
            assertHasFoldAnimationAtTheEnd()
        }
    }

    private class TestUnfoldProgressListener : TransitionProgressListener {

        private val recordings: MutableList<UnfoldTransitionRecording> = arrayListOf()
        private var currentRecording: UnfoldTransitionRecording? = null

        override fun onTransitionStarted() {
            assertWithMessage("Trying to start a transition when it is already in progress")
                .that(currentRecording).isNull()

            currentRecording = UnfoldTransitionRecording()
        }

        override fun onTransitionProgress(progress: Float) {
            assertWithMessage("Received transition progress event when it's not started")
                .that(currentRecording).isNotNull()
            currentRecording!!.addProgress(progress)
        }

        override fun onTransitionFinishing() {
            assertWithMessage("Received transition finishing event when it's not started")
                    .that(currentRecording).isNotNull()
            currentRecording!!.onFinishing()
        }

        override fun onTransitionFinished() {
            assertWithMessage("Received transition finish event when it's not started")
                .that(currentRecording).isNotNull()
            recordings += currentRecording!!
            currentRecording = null
        }

        fun ensureTransitionFinished(): UnfoldTransitionRecording {
            waitForCondition { recordings.size == 1 }
            return recordings.first()
        }

        class UnfoldTransitionRecording {
            private val progressHistory: MutableList<Float> = arrayListOf()
            private var finishingInvocations: Int = 0

            fun addProgress(progress: Float) {
                assertThat(progress).isAtMost(1.0f)
                assertThat(progress).isAtLeast(0.0f)

                progressHistory += progress
            }

            fun onFinishing() {
                finishingInvocations++
            }

            fun assertIncreasingProgress() {
                assertThat(progressHistory.size).isGreaterThan(MIN_ANIMATION_EVENTS)
                assertThat(progressHistory).isInOrder()
            }

            fun assertDecreasingProgress() {
                assertThat(progressHistory.size).isGreaterThan(MIN_ANIMATION_EVENTS)
                assertThat(progressHistory).isInOrder(Comparator.reverseOrder<Float>())
            }

            fun assertFinishedWithUnfold() {
                assertThat(progressHistory).isNotEmpty()
                assertThat(progressHistory.last()).isEqualTo(1.0f)
            }

            fun assertFinishedWithFold() {
                assertThat(progressHistory).isNotEmpty()
                assertThat(progressHistory.last()).isEqualTo(0.0f)
            }

            fun assertHasFoldAnimationAtTheEnd() {
                // Check that there are at least a few decreasing events at the end
                assertThat(progressHistory.size).isGreaterThan(MIN_ANIMATION_EVENTS)
                assertThat(progressHistory.takeLast(MIN_ANIMATION_EVENTS))
                    .isInOrder(Comparator.reverseOrder<Float>())
                assertThat(progressHistory.last()).isEqualTo(0.0f)
            }

            fun assertHasSingleFinishingEvent() {
                assertWithMessage("onTransitionFinishing callback should be invoked exactly " +
                        "one time").that(finishingInvocations).isEqualTo(1)
            }
        }

        private companion object {
            private const val MIN_ANIMATION_EVENTS = 5
        }
    }

    private fun runOnMainThreadWithInterval(vararg blocks: () -> Unit, intervalMillis: Long = 60) {
        blocks.forEach {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                it()
            }
            Thread.sleep(intervalMillis)
        }
    }
}
