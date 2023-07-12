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
package com.android.dream.lowlight

import android.animation.Animator
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.dream.lowlight.LowLightTransitionCoordinator.LowLightEnterListener
import com.android.dream.lowlight.LowLightTransitionCoordinator.LowLightExitListener
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import src.com.android.dream.lowlight.utils.whenever
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
class LowLightTransitionCoordinatorTest {
    @Mock
    private lateinit var mEnterListener: LowLightEnterListener

    @Mock
    private lateinit var mExitListener: LowLightExitListener

    @Mock
    private lateinit var mAnimator: Animator

    @Captor
    private lateinit var mAnimatorListenerCaptor: ArgumentCaptor<Animator.AnimatorListener>

    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope(StandardTestDispatcher())
    }

    @Test
    fun onEnterCalledOnListeners() = testScope.runTest {
        val coordinator = LowLightTransitionCoordinator()
        coordinator.setLowLightEnterListener(mEnterListener)
        val job = launch {
            coordinator.waitForLowLightTransitionAnimation(timeout = TIMEOUT, entering = true)
        }
        runCurrent()
        verify(mEnterListener).onBeforeEnterLowLight()
        assertThat(job.isCompleted).isTrue()
    }

    @Test
    fun onExitCalledOnListeners() = testScope.runTest {
        val coordinator = LowLightTransitionCoordinator()
        coordinator.setLowLightExitListener(mExitListener)
        val job = launch {
            coordinator.waitForLowLightTransitionAnimation(timeout = TIMEOUT, entering = false)
        }
        runCurrent()
        verify(mExitListener).onBeforeExitLowLight()
        assertThat(job.isCompleted).isTrue()
    }

    @Test
    fun listenerNotCalledAfterRemoval() = testScope.runTest {
        val coordinator = LowLightTransitionCoordinator()
        coordinator.setLowLightEnterListener(mEnterListener)
        coordinator.setLowLightEnterListener(null)
        val job = launch {
            coordinator.waitForLowLightTransitionAnimation(timeout = TIMEOUT, entering = true)
        }
        runCurrent()
        verify(mEnterListener, never()).onBeforeEnterLowLight()
        assertThat(job.isCompleted).isTrue()
    }

    @Test
    fun waitsForAnimationToEnd() = testScope.runTest {
        whenever(mEnterListener.onBeforeEnterLowLight()).thenReturn(mAnimator)
        val coordinator = LowLightTransitionCoordinator()
        coordinator.setLowLightEnterListener(mEnterListener)
        val job = launch {
            coordinator.waitForLowLightTransitionAnimation(timeout = TIMEOUT, entering = true)
        }
        runCurrent()
        // Animator listener is added and the runnable is not run yet.
        verify(mAnimator).addListener(mAnimatorListenerCaptor.capture())
        assertThat(job.isCompleted).isFalse()

        // Runnable is run once the animation ends.
        mAnimatorListenerCaptor.value.onAnimationEnd(mAnimator)
        runCurrent()
        assertThat(job.isCompleted).isTrue()
        assertThat(job.isCancelled).isFalse()
    }

    @Test
    fun waitsForTimeoutIfAnimationNeverEnds() = testScope.runTest {
        whenever(mEnterListener.onBeforeEnterLowLight()).thenReturn(mAnimator)
        val coordinator = LowLightTransitionCoordinator()
        coordinator.setLowLightEnterListener(mEnterListener)
        val job = launch {
            coordinator.waitForLowLightTransitionAnimation(timeout = TIMEOUT, entering = true)
        }
        runCurrent()
        assertThat(job.isCancelled).isFalse()
        advanceTimeBy(delayTimeMillis = TIMEOUT.inWholeMilliseconds + 1)
        // If animator doesn't complete within the timeout, we should cancel ourselves.
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun shouldCancelIfAnimationIsCancelled() = testScope.runTest {
        whenever(mEnterListener.onBeforeEnterLowLight()).thenReturn(mAnimator)
        val coordinator = LowLightTransitionCoordinator()
        coordinator.setLowLightEnterListener(mEnterListener)
        val job = launch {
            coordinator.waitForLowLightTransitionAnimation(timeout = TIMEOUT, entering = true)
        }
        runCurrent()
        // Animator listener is added and the runnable is not run yet.
        verify(mAnimator).addListener(mAnimatorListenerCaptor.capture())
        assertThat(job.isCompleted).isFalse()
        assertThat(job.isCancelled).isFalse()

        // Runnable is run once the animation ends.
        mAnimatorListenerCaptor.value.onAnimationCancel(mAnimator)
        runCurrent()
        assertThat(job.isCompleted).isTrue()
        assertThat(job.isCancelled).isTrue()
    }

    companion object {
        private val TIMEOUT = 1.toDuration(DurationUnit.SECONDS)
    }
}
