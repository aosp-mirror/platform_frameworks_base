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

package com.android.systemui.unfold.progress

import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.util.leak.ReferenceTestUtils.waitForCondition
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage

/** Listener usable by tests with some handy assertions. */
class TestUnfoldProgressListener : UnfoldTransitionProgressProvider.TransitionProgressListener {

    private val recordings: MutableList<UnfoldTransitionRecording> = arrayListOf()
    private var currentRecording: UnfoldTransitionRecording? = null
    var lastCallbackThread: Thread? = null
        private set

    override fun onTransitionStarted() {
        lastCallbackThread = Thread.currentThread()
        assertWithMessage("Trying to start a transition when it is already in progress")
            .that(currentRecording)
            .isNull()

        currentRecording = UnfoldTransitionRecording()
    }

    override fun onTransitionProgress(progress: Float) {
        lastCallbackThread = Thread.currentThread()
        assertWithMessage("Received transition progress event when it's not started")
            .that(currentRecording)
            .isNotNull()
        currentRecording!!.addProgress(progress)
    }

    override fun onTransitionFinishing() {
        lastCallbackThread = Thread.currentThread()
        assertWithMessage("Received transition finishing event when it's not started")
            .that(currentRecording)
            .isNotNull()
        currentRecording!!.onFinishing()
    }

    override fun onTransitionFinished() {
        lastCallbackThread = Thread.currentThread()
        assertWithMessage("Received transition finish event when it's not started")
            .that(currentRecording)
            .isNotNull()
        recordings += currentRecording!!
        currentRecording = null
    }

    fun ensureTransitionFinished(): UnfoldTransitionRecording {
        waitForCondition { recordings.size == 1 }
        return recordings.first()
    }

    /**
     * Number of progress event for the currently running transition
     * Returns null if there is no currently running transition
     */
    val currentTransitionProgressEventCount: Int?
        get() = currentRecording?.progressHistory?.size

    /**
     * Runs [block] and ensures that there was at least once onTransitionProgress event after that
     */
    fun waitForProgressChangeAfter(block: () -> Unit) {
        val eventCount = currentTransitionProgressEventCount
        block()
        waitForCondition {
            currentTransitionProgressEventCount != eventCount
        }
    }

    fun assertStarted() {
        assertWithMessage("Transition didn't start").that(currentRecording).isNotNull()
    }

    fun assertNotStarted() {
        assertWithMessage("Transition started").that(currentRecording).isNull()
    }

    fun assertLastProgress(progress: Float) {
        currentRecording?.assertLastProgress(progress) ?: error("unfold not in progress.")
    }

    fun clear() {
        currentRecording = null
        recordings.clear()
    }

    class UnfoldTransitionRecording {
        val progressHistory: MutableList<Float> = arrayListOf()
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
            assertWithMessage(
                    "onTransitionFinishing callback should be invoked exactly " + "one time"
                )
                .that(finishingInvocations)
                .isEqualTo(1)
        }

        fun assertLastProgress(progress: Float) {
            waitForCondition { progress == progressHistory.lastOrNull() }
        }
    }

    private companion object {
        private const val MIN_ANIMATION_EVENTS = 3
    }
}
