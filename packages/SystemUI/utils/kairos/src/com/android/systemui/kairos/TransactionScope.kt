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

/**
 * Kairos operations that are available while a transaction is active.
 *
 * These operations do not accumulate state, which makes [TransactionScope] weaker than
 * [StateScope], but allows them to be used in more places.
 */
@ExperimentalKairosApi
interface TransactionScope : KairosScope {

    /**
     * Returns the current value of this [Transactional] as a [DeferredValue].
     *
     * Compared to [sample], you may want to use this instead if you do not need to inspect the
     * sampled value, but instead want to pass it to another Kairos API that accepts a
     * [DeferredValue]. In this case, [sampleDeferred] is both safer and more performant.
     *
     * @see sample
     */
    fun <A> Transactional<A>.sampleDeferred(): DeferredValue<A>

    /**
     * Returns the current value of this [State] as a [DeferredValue].
     *
     * Compared to [sample], you may want to use this instead if you do not need to inspect the
     * sampled value, but instead want to pass it to another Kairos API that accepts a
     * [DeferredValue]. In this case, [sampleDeferred] is both safer and more performant.
     *
     * @see sample
     */
    fun <A> State<A>.sampleDeferred(): DeferredValue<A>

    /**
     * Defers invoking [block] until after the current [TransactionScope] code-path completes,
     * returning a [DeferredValue] that can be used to reference the result.
     *
     * Useful for recursive definitions.
     *
     * @see DeferredValue
     */
    fun <A> deferredTransactionScope(block: TransactionScope.() -> A): DeferredValue<A>

    /** An [Events] that emits once, within this transaction, and then never again. */
    val now: Events<Unit>

    /**
     * Returns the current value held by this [State]. Guaranteed to be consistent within the same
     * transaction.
     *
     * @see sampleDeferred
     */
    fun <A> State<A>.sample(): A = sampleDeferred().get()

    /**
     * Returns the current value held by this [Transactional]. Guaranteed to be consistent within
     * the same transaction.
     *
     * @see sampleDeferred
     */
    fun <A> Transactional<A>.sample(): A = sampleDeferred().get()
}
