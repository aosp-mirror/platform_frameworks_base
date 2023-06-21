/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.common.coroutine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * Calls the specified function [block] and returns its encapsulated result if invocation was
 * successful, catching any [Throwable] exception that was thrown from the block function execution
 * and encapsulating it as a failure.
 *
 * Unlike [runCatching], [suspendRunCatching] does not break structured concurrency by rethrowing
 * any [CancellationException].
 *
 * **Heads-up:** [TimeoutCancellationException] extends [CancellationException] but catching it does
 * not breaks structured concurrency and therefore, will not be rethrown. Therefore, you can use
 * [suspendRunCatching] with [withTimeout], and handle any timeout gracefully.
 *
 * @see <a href="https://github.com/Kotlin/kotlinx.coroutines/issues/1814">link</a>
 */
suspend inline fun <T> suspendRunCatching(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Throwable) {
        // Ensures the try-catch block will not break structured concurrency.
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

/**
 * Calls the specified function [block] and returns its encapsulated result if invocation was
 * successful, catching any [Throwable] exception that was thrown from the block function execution
 * and encapsulating it as a failure.
 *
 * Unlike [runCatching], [suspendRunCatching] does not break structured concurrency by rethrowing
 * any [CancellationException].
 *
 * **Heads-up:** [TimeoutCancellationException] extends [CancellationException] but catching it does
 * not breaks structured concurrency and therefore, will not be rethrown. Therefore, you can use
 * [suspendRunCatching] with [withTimeout], and handle any timeout gracefully.
 *
 * @see <a href="https://github.com/Kotlin/kotlinx.coroutines/issues/1814">link</a>
 */
suspend inline fun <T, R> T.suspendRunCatching(crossinline block: suspend T.() -> R): Result<R> =
    // Overload with a `this` receiver, matches with `kotlin.runCatching` functions.
    // Qualified name needs to be used to avoid a recursive call.
    com.android.systemui.common.coroutine.suspendRunCatching { block(this) }
