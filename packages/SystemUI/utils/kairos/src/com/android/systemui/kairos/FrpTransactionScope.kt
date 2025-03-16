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

/**
 * FRP operations that are available while a transaction is active.
 *
 * These operations do not accumulate state, which makes [FrpTransactionScope] weaker than
 * [FrpStateScope], but allows them to be used in more places.
 */
@ExperimentalFrpApi
@RestrictsSuspension
interface FrpTransactionScope : FrpScope {

    /**
     * Returns the current value of this [Transactional] as a [FrpDeferredValue].
     *
     * @see sample
     */
    @ExperimentalFrpApi fun <A> Transactional<A>.sampleDeferred(): FrpDeferredValue<A>

    /**
     * Returns the current value of this [TState] as a [FrpDeferredValue].
     *
     * @see sample
     */
    @ExperimentalFrpApi fun <A> TState<A>.sampleDeferred(): FrpDeferredValue<A>

    /** TODO */
    @ExperimentalFrpApi
    fun <A> deferredTransactionScope(
        block: suspend FrpTransactionScope.() -> A
    ): FrpDeferredValue<A>

    /** A [TFlow] that emits once, within this transaction, and then never again. */
    @ExperimentalFrpApi val now: TFlow<Unit>

    /**
     * Returns the current value held by this [TState]. Guaranteed to be consistent within the same
     * transaction.
     */
    @ExperimentalFrpApi suspend fun <A> TState<A>.sample(): A = sampleDeferred().get()

    /**
     * Returns the current value held by this [Transactional]. Guaranteed to be consistent within
     * the same transaction.
     */
    @ExperimentalFrpApi suspend fun <A> Transactional<A>.sample(): A = sampleDeferred().get()
}
