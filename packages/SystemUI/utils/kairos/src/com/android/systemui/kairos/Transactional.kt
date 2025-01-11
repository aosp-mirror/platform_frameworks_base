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

import com.android.systemui.kairos.internal.CompletableLazy
import com.android.systemui.kairos.internal.InitScope
import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.TransactionalImpl
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.transactionalImpl
import com.android.systemui.kairos.internal.util.hashString

/**
 * A time-varying value. A [Transactional] encapsulates the idea of some continuous state; each time
 * it is "sampled", a new result may be produced.
 *
 * Because Kairos operates over an "idealized" model of Time that can be passed around as a data
 * type, [Transactionals][Transactional] are guaranteed to produce the same result if queried
 * multiple times at the same (conceptual) time, in order to preserve _referential transparency_.
 */
@ExperimentalKairosApi
class Transactional<out A> internal constructor(internal val impl: State<TransactionalImpl<A>>) {
    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

/** A constant [Transactional] that produces [value] whenever it is sampled. */
@ExperimentalKairosApi
fun <A> transactionalOf(value: A): Transactional<A> =
    Transactional(stateOf(TransactionalImpl.Const(CompletableLazy(value))))

/**
 * Returns a [Transactional] that acts as a deferred-reference to the [Transactional] produced by
 * this [DeferredValue].
 *
 * When the returned [Transactional] is accessed by the Kairos network, the [DeferredValue] will be
 * queried and used.
 *
 * Useful for recursive definitions.
 *
 * ```
 *   fun <A> DeferredValue<Transactional<A>>.defer() = deferredTransactional { get() }
 * ```
 */
@ExperimentalKairosApi
fun <A> DeferredValue<Transactional<A>>.defer(): Transactional<A> = deferInline { unwrapped.value }

/**
 * Returns a [Transactional] that acts as a deferred-reference to the [Transactional] produced by
 * this [Lazy].
 *
 * When the returned [Transactional] is accessed by the Kairos network, the [Lazy]'s
 * [value][Lazy.value] will be queried and used.
 *
 * Useful for recursive definitions.
 *
 * ```
 *   fun <A> Lazy<Transactional<A>>.defer() = deferredTransactional { value }
 * ```
 */
@ExperimentalKairosApi
fun <A> Lazy<Transactional<A>>.defer(): Transactional<A> = deferInline { value }

/**
 * Returns a [Transactional] that acts as a deferred-reference to the [Transactional] produced by
 * [block].
 *
 * When the returned [Transactional] is accessed by the Kairos network, [block] will be invoked and
 * the returned [Transactional] will be used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi
fun <A> deferredTransactional(block: KairosScope.() -> Transactional<A>): Transactional<A> =
    deferInline {
        NoScope.block()
    }

private inline fun <A> deferInline(
    crossinline block: InitScope.() -> Transactional<A>
): Transactional<A> =
    Transactional(StateInit(init(name = null) { block().impl.init.connect(evalScope = this) }))

/**
 * Returns a [Transactional]. The passed [block] will be evaluated on demand at most once per
 * transaction; any subsequent sampling within the same transaction will receive a cached value.
 *
 * @sample com.android.systemui.kairos.KairosSamples.sampleTransactional
 */
@ExperimentalKairosApi
fun <A> transactionally(block: TransactionScope.() -> A): Transactional<A> =
    Transactional(stateOf(transactionalImpl { block() }))

/** Returns a [Transactional] that, when queried, samples this [State]. */
fun <A> State<A>.asTransactional(): Transactional<A> =
    Transactional(map { TransactionalImpl.Const(CompletableLazy(it)) })
