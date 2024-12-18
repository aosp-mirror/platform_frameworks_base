/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.kairos

import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine

/** Denotes [FrpScope] interfaces as [DSL markers][DslMarker]. */
@DslMarker annotation class FrpScopeMarker

/**
 * Base scope for all FRP scopes. Used to prevent implicitly capturing other scopes from in lambdas.
 */
@FrpScopeMarker
@RestrictsSuspension
@ExperimentalFrpApi
interface FrpScope {
    /**
     * Returns the value held by the [FrpDeferredValue], suspending until available if necessary.
     */
    @ExperimentalFrpApi
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <A> FrpDeferredValue<A>.get(): A = suspendCancellableCoroutine { k ->
        unwrapped.invokeOnCompletion { ex ->
            ex?.let { k.resumeWithException(ex) } ?: k.resume(unwrapped.getCompleted())
        }
    }
}

/**
 * A value that may not be immediately (synchronously) available, but is guaranteed to be available
 * before this transaction is completed.
 *
 * @see FrpScope.get
 */
@ExperimentalFrpApi
class FrpDeferredValue<out A> internal constructor(internal val unwrapped: Deferred<A>)

/**
 * Returns the value held by this [FrpDeferredValue], or throws [IllegalStateException] if it is not
 * yet available.
 *
 * This API is not meant for general usage within the FRP network. It is made available mainly for
 * debugging and logging. You should always prefer [get][FrpScope.get] if possible.
 *
 * @see FrpScope.get
 */
@ExperimentalFrpApi
@OptIn(ExperimentalCoroutinesApi::class)
fun <A> FrpDeferredValue<A>.getUnsafe(): A = unwrapped.getCompleted()

/** Returns an already-available [FrpDeferredValue] containing [value]. */
@ExperimentalFrpApi
fun <A> deferredOf(value: A): FrpDeferredValue<A> = FrpDeferredValue(CompletableDeferred(value))
