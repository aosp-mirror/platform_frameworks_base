/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.inputmethod

import android.annotation.DurationMillisLong
import android.os.Handler
import android.os.SystemClock
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@DurationMillisLong
const val SHORT_PERIOD_MILLI = 50L
const val SHORT_PERIOD_NANO = SHORT_PERIOD_MILLI * 1_000_000L

@DurationMillisLong
const val TIMEOUT_MILLI = 10_000L
const val TIMEOUT_NANO = TIMEOUT_MILLI * 1_000_000L

const val ERROR_MESSAGE = "Test Error Message!"

@LargeTest
@RunWith(AndroidJUnit4::class)
class CompletableFutureUtilTest {

    private inline fun assertRuntimeException(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail()
        } catch (exception: RuntimeException) {
            assertThat(exception.message).isEqualTo(expectedMessage)
            // Expected
        } catch (exception: Throwable) {
            fail("RuntimeException is expected but got $exception")
        }
    }

    private inline fun runOnMainDelayed(delay: Long, crossinline block: () -> Unit) {
        val handler = Handler.createAsync(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getMainLooper())
        handler.postDelayed({
            block()
        }, delay)
    }

    @Test
    fun testCharSequenceTimedOut() {
        val completable = CompletableFuture<CharSequence>()

        assertThat(completable.isDone).isFalse()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, null, SHORT_PERIOD_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        assertThat(completable.isDone).isFalse()
        assertThat(result).isNull()
        assertThat(elapsed).isGreaterThan(SHORT_PERIOD_NANO)
    }

    @Test
    fun testCharSequenceTimedOutWithInterruption() {
        val completable = CompletableFuture<CharSequence>()

        val beginNanosRef = AtomicLong()
        val endNanosRef = AtomicLong()
        val isInterruptedRef = AtomicBoolean()
        val resultRef = AtomicReference<CharSequence>()

        // Verifies that calling getResultOrNull() on an interrupted thread still times out with
        // preserving the interrupted state.
        val thread = Thread {
            val currentThread = Thread.currentThread()
            currentThread.interrupt()
            beginNanosRef.set(SystemClock.elapsedRealtimeNanos())
            resultRef.set(CompletableFutureUtil.getResultOrNull(
                    completable, null, null, null, SHORT_PERIOD_MILLI))
            endNanosRef.set(SystemClock.elapsedRealtimeNanos())
            isInterruptedRef.set(currentThread.isInterrupted())
        }

        thread.run()
        thread.join(TIMEOUT_MILLI)
        assertThat(thread.isAlive).isFalse()

        val elapsedTime = endNanosRef.get() - beginNanosRef.get()
        assertThat(elapsedTime).isGreaterThan(SHORT_PERIOD_NANO)
        assertThat(resultRef.get()).isNull()
        assertThat(isInterruptedRef.get()).isTrue()
    }

    @Test
    fun testCharSequenceAfterCompletion() {
        val expectedValue = "Expected Value"
        val completable = CompletableFuture<CharSequence>()

        assertThat(completable.isDone).isFalse()
        completable.complete(expectedValue)
        assertThat(completable.isDone).isTrue()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, null,
                TIMEOUT_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        assertThat(result).isEqualTo(expectedValue)
        assertThat(elapsed).isLessThan(SHORT_PERIOD_NANO)
    }

    @Test
    fun testCharSequenceAfterError() {
        val completable = CompletableFuture<CharSequence>()

        assertThat(completable.isDone).isFalse()
        completable.completeExceptionally(UnsupportedOperationException(ERROR_MESSAGE))
        assertThat(completable.isDone).isTrue()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, null, TIMEOUT_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        assertThat(result).isNull()
        assertThat(elapsed).isLessThan(SHORT_PERIOD_NANO)

        assertRuntimeException(ERROR_MESSAGE) {
            CompletableFutureUtil.getResult(completable)
        }
    }

    @Test
    fun testCharSequenceAfterCancellation() {
        val completable = CompletableFuture<CharSequence>()
        val cancellationGroup = CancellationGroup()
        cancellationGroup.cancelAll()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, cancellationGroup, TIMEOUT_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        // due to the side-effect of cancellationGroup, the object is already completed here.
        assertThat(completable.isDone).isTrue()
        assertThat(result).isNull()
        assertThat(elapsed).isLessThan(SHORT_PERIOD_NANO)

        // as the object is already cancelled due to the side-effect of cancellationGroup, it cannot
        // accept a result any more.
        completable.complete("Hello!")
        assertThat(completable.isCancelled).isTrue()
    }

    @Test
    fun testCharSequenceAfterCompleteAndCancellation() {
        val expectedValue = "Expected Value"
        val completable = CompletableFuture<CharSequence>()
        completable.complete(expectedValue)

        val cancellationGroup = CancellationGroup()
        cancellationGroup.cancelAll()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, cancellationGroup, TIMEOUT_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        assertThat(result).isEqualTo(expectedValue)
        assertThat(CompletableFutureUtil.getResult(completable)).isEqualTo(expectedValue)
        assertThat(elapsed).isLessThan(SHORT_PERIOD_NANO)
    }

    @Test
    fun testCharSequenceMultipleAssignment() {
        val expectedValue = "Expected Value"
        val notExpectedValue = "Not Expected Value"
        val completable = CompletableFuture<CharSequence>()
        completable.complete(expectedValue)
        completable.complete(notExpectedValue)
        assertThat(completable.isDone).isTrue()

        assertThat(CompletableFutureUtil.getResult(completable)).isEqualTo(expectedValue)
    }

    @Test
    fun testCharSequenceUnblockByCompletion() {
        val expectedValue = "Expected Value"
        val completable = CompletableFuture<CharSequence>()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        runOnMainDelayed(SHORT_PERIOD_MILLI) {
            completable.complete(expectedValue)
        }
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, null, TIMEOUT_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        assertThat(completable.isDone).isTrue()
        assertThat(result).isEqualTo(expectedValue)
        assertThat(elapsed).isIn(Range.closedOpen(SHORT_PERIOD_NANO, TIMEOUT_NANO))
    }

    @Test
    fun testCharSequenceUnblockByCompletionWithCancellationGroup() {
        val expectedValue = "Expected Value"
        val completable = CompletableFuture<CharSequence>()
        var cancellationGroup = CancellationGroup()

        assertThat(cancellationGroup.isCanceled).isFalse()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        runOnMainDelayed(SHORT_PERIOD_MILLI) {
            completable.complete(expectedValue)
        }
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, cancellationGroup, TIMEOUT_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        assertThat(cancellationGroup.isCanceled).isFalse()
        assertThat(completable.isDone).isTrue()
        assertThat(result).isEqualTo(expectedValue)
        assertThat(elapsed).isIn(Range.closedOpen(SHORT_PERIOD_NANO, TIMEOUT_NANO))
    }

    @Test
    fun testCharSequenceUnblockByError() {
        val completable = CompletableFuture<CharSequence>()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        runOnMainDelayed(SHORT_PERIOD_MILLI) {
            completable.completeExceptionally(UnsupportedOperationException(ERROR_MESSAGE))
        }
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, null, TIMEOUT_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        assertThat(completable.isDone).isTrue()
        assertThat(result).isNull()
        assertThat(elapsed).isIn(Range.closedOpen(SHORT_PERIOD_NANO, TIMEOUT_NANO))
    }

    @Test
    fun testCharSequenceUnblockByCancellation() {
        val completable = CompletableFuture<CharSequence>()
        val cancellationGroup = CancellationGroup()

        val beginNanos = SystemClock.elapsedRealtimeNanos()
        runOnMainDelayed(SHORT_PERIOD_MILLI) {
            cancellationGroup.cancelAll()
        }
        val result = CompletableFutureUtil.getResultOrNull(
                completable, null, null, cancellationGroup, TIMEOUT_MILLI)
        val elapsed = SystemClock.elapsedRealtimeNanos() - beginNanos

        // due to the side-effect of cancellationGroup.
        assertThat(completable.isDone).isTrue()
        assertThat(result).isNull()
        assertThat(elapsed).isIn(Range.closedOpen(SHORT_PERIOD_NANO, TIMEOUT_NANO))
    }
}
