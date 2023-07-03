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

package com.android.systemui.keyguard.util

import android.animation.AnimationHandler.AnimationFrameCallbackProvider
import android.animation.ValueAnimator
import android.view.Choreographer.FrameCallback
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.TransitionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.fail

/**
 * Gives direct control over ValueAnimator, in order to make transition tests deterministic. See
 * [AnimationHandler]. Animators are required to be run on the main thread, so dispatch accordingly.
 */
class KeyguardTransitionRunner(
    val repository: KeyguardTransitionRepository,
) : AnimationFrameCallbackProvider {

    private var frameCount = 1L
    private var frames = MutableStateFlow(Pair<Long, FrameCallback?>(0L, null))
    private var job: Job? = null
    private var isTerminated = false

    /**
     * For transitions being directed by an animator. Will control the number of frames being
     * generated so the values are deterministic.
     */
    suspend fun startTransition(scope: CoroutineScope, info: TransitionInfo, maxFrames: Int = 100) {
        // AnimationHandler uses ThreadLocal storage, and ValueAnimators MUST start from main
        // thread
        withContext(Dispatchers.Main) {
            info.animator!!.getAnimationHandler().setProvider(this@KeyguardTransitionRunner)
        }

        job =
            scope.launch {
                frames.collect {
                    val (frameNumber, callback) = it

                    isTerminated = frameNumber >= maxFrames
                    if (!isTerminated) {
                        withContext(Dispatchers.Main) { callback?.doFrame(frameNumber) }
                    }
                }
            }
        withContext(Dispatchers.Main) { repository.startTransition(info) }

        waitUntilComplete(info.animator!!)
    }

    private suspend fun waitUntilComplete(animator: ValueAnimator) {
        withContext(Dispatchers.Main) {
            val startTime = System.currentTimeMillis()
            while (!isTerminated && animator.isRunning()) {
                delay(1)
                if (System.currentTimeMillis() - startTime > MAX_TEST_DURATION) {
                    fail("Failed test due to excessive runtime of: $MAX_TEST_DURATION")
                }
            }

            animator.getAnimationHandler().setProvider(null)
        }

        job?.cancel()
    }

    override fun postFrameCallback(cb: FrameCallback) {
        frames.value = Pair(frameCount++, cb)
    }
    override fun postCommitCallback(runnable: Runnable) {}
    override fun getFrameTime() = frameCount
    override fun getFrameDelay() = 1L
    override fun setFrameDelay(delay: Long) {}

    companion object {
        private const val MAX_TEST_DURATION = 200L
    }
}
