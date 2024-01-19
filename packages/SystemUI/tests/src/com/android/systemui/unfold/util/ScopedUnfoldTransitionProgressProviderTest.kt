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

package com.android.systemui.unfold.util

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.TestUnfoldTransitionProvider
import com.android.systemui.unfold.progress.TestUnfoldProgressListener
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class ScopedUnfoldTransitionProgressProviderTest : SysuiTestCase() {

    private val rootProvider = TestUnfoldTransitionProvider()
    private val listener = TestUnfoldProgressListener()
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val bgThread =
        HandlerThread("UnfoldBgTest", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val bgHandler = Handler(bgThread.looper)
    private val scopedProvider =
        ScopedUnfoldTransitionProgressProvider(rootProvider).apply { addCallback(listener) }

    @Test
    fun setReadyToHandleTransition_whileTransitionRunning_propagatesCallbacks() =
        testScope.runTest {
            runBlockingInBg { rootProvider.onTransitionStarted() }

            scopedProvider.setReadyToHandleTransition(true)

            runBlockingInBg { /* sync barrier */}

            listener.assertStarted()

            runBlockingInBg { rootProvider.onTransitionProgress(1f) }

            listener.assertLastProgress(1f)

            runBlockingInBg { rootProvider.onTransitionFinished() }

            listener.assertNotStarted()
        }

    @Test
    fun setReadyToHandleTransition_whileTransitionNotRunning_callbacksInProgressThread() {
        testScope.runTest {
            scopedProvider.setReadyToHandleTransition(true)

            val bgThread = runBlockingInBg { Thread.currentThread() }

            runBlockingInBg { rootProvider.onTransitionStarted() }

            listener.assertStarted()

            assertThat(listener.lastCallbackThread).isEqualTo(bgThread)
        }
    }

    @Test
    fun setReadyToHandleTransition_whileTransitionRunning_fromBgThread_propagatesCallbacks() =
        testScope.runTest {
            runBlockingInBg { rootProvider.onTransitionStarted() }

            runBlockingInBg {
                // This causes the transition started callback to be propagated immediately, without
                // the need to switch thread (as we're already in the correct one). We don't need a
                // sync barrier on the bg thread as in
                // setReadyToHandleTransition_whileTransitionRunning_propagatesCallbacks here.
                scopedProvider.setReadyToHandleTransition(true)
            }

            listener.assertStarted()

            runBlockingInBg { rootProvider.onTransitionProgress(1f) }

            listener.assertLastProgress(1f)

            runBlockingInBg { rootProvider.onTransitionFinished() }

            listener.assertNotStarted()
        }

    @Test
    fun setReadyToHandleTransition_beforeAnyCallback_doesNotCrash() {
        testScope.runTest { scopedProvider.setReadyToHandleTransition(true) }
    }

    @Test
    fun onTransitionStarted_whileNotReadyToHandleTransition_doesNotPropagate() {
        testScope.runTest {
            scopedProvider.setReadyToHandleTransition(false)

            rootProvider.onTransitionStarted()

            listener.assertNotStarted()
        }
    }

    @Test
    fun onTransitionStarted_defaultReadiness_doesNotPropagate() {
        testScope.runTest {
            rootProvider.onTransitionStarted()

            listener.assertNotStarted()
        }
    }

    @Test
    fun onTransitionStarted_fromDifferentThreads_throws() {
        testScope.runTest {
            runBlockingInBg {
                rootProvider.onTransitionStarted()
                rootProvider.onTransitionFinished()
            }
            assertThrows(IllegalStateException::class.java) { rootProvider.onTransitionStarted() }
        }
    }

    @Test
    fun onTransitionProgress_fromDifferentThreads_throws() {
        testScope.runTest {
            runBlockingInBg { rootProvider.onTransitionStarted() }
            assertThrows(IllegalStateException::class.java) {
                rootProvider.onTransitionProgress(1f)
            }
        }
    }

    @Test
    fun onTransitionFinished_fromDifferentThreads_throws() {
        testScope.runTest {
            runBlockingInBg { rootProvider.onTransitionStarted() }
            assertThrows(IllegalStateException::class.java) { rootProvider.onTransitionFinished() }
        }
    }

    @Test
    fun onTransitionFinishing_fromDifferentThreads_throws() {
        testScope.runTest {
            runBlockingInBg { rootProvider.onTransitionStarted() }
            assertThrows(IllegalStateException::class.java) { rootProvider.onTransitionFinishing() }
        }
    }

    private fun <T> runBlockingInBg(f: () -> T): T {
        return runBlocking {
            withTimeout(5.seconds) {
                suspendCancellableCoroutine { c: CancellableContinuation<T> ->
                    bgHandler.post { c.resumeWith(Result.success(f())) }
                }
            }
        }
    }
}
