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
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    val frames: Flow<Long>,
    val repository: KeyguardTransitionRepository,
) {
    @Volatile private var isTerminated = false

    /**
     * For transitions being directed by an animator. Will control the number of frames being
     * generated so the values are deterministic.
     */
    suspend fun startTransition(
        scope: CoroutineScope,
        info: TransitionInfo,
        maxFrames: Int = 100,
        frameCallback: Consumer<Long>? = null,
    ) {
        val job =
            scope.launch {
                frames.collect { frameNumber ->
                    isTerminated = frameNumber >= maxFrames
                    if (!isTerminated) {
                        try {
                            frameCallback?.accept(frameNumber)
                        } catch (e: IllegalStateException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        withContext(Dispatchers.Main) { repository.startTransition(info) }

        waitUntilComplete(info, info.animator!!)
        job.cancel()
    }

    private suspend fun waitUntilComplete(info: TransitionInfo, animator: ValueAnimator) {
        withContext(Dispatchers.Main) {
            val startTime = System.currentTimeMillis()
            while (!isTerminated && animator.isRunning()) {
                delay(1)
                if (System.currentTimeMillis() - startTime > MAX_TEST_DURATION) {
                    fail("Failed due to excessive runtime of: $MAX_TEST_DURATION, info: $info")
                }
            }
        }
    }

    companion object {
        private const val MAX_TEST_DURATION = 300L
    }
}

class FrameCallbackProvider(val scope: CoroutineScope) : AnimationFrameCallbackProvider {
    private val callback = MutableSharedFlow<FrameCallback?>(replay = 2)
    private var frameCount = 0L
    val frames = MutableStateFlow(frameCount)

    init {
        scope.launch {
            callback.collect {
                withContext(Dispatchers.Main) {
                    delay(1)
                    it?.doFrame(frameCount)
                }
            }
        }
    }

    override fun postFrameCallback(cb: FrameCallback) {
        frames.value = ++frameCount
        callback.tryEmit(cb)
    }

    override fun postCommitCallback(runnable: Runnable) {}

    override fun getFrameTime() = frameCount

    override fun getFrameDelay() = 1L

    override fun setFrameDelay(delay: Long) {}
}
