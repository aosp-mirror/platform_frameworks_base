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

import com.android.systemui.kairos.internal.InitScope
import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.TransactionalImpl
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.transactionalImpl
import com.android.systemui.kairos.internal.util.hashString
import kotlinx.coroutines.CompletableDeferred

/**
 * A time-varying value. A [Transactional] encapsulates the idea of some continuous state; each time
 * it is "sampled", a new result may be produced.
 *
 * Because FRP operates over an "idealized" model of Time that can be passed around as a data type,
 * [Transactional]s are guaranteed to produce the same result if queried multiple times at the same
 * (conceptual) time, in order to preserve _referential transparency_.
 */
@ExperimentalFrpApi
class Transactional<out A> internal constructor(internal val impl: TState<TransactionalImpl<A>>) {
    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

/** A constant [Transactional] that produces [value] whenever it is sampled. */
@ExperimentalFrpApi
fun <A> transactionalOf(value: A): Transactional<A> =
    Transactional(tStateOf(TransactionalImpl.Const(CompletableDeferred(value))))

/** TODO */
@ExperimentalFrpApi
fun <A> FrpDeferredValue<Transactional<A>>.defer(): Transactional<A> = deferInline {
    unwrapped.await()
}

/** TODO */
@ExperimentalFrpApi fun <A> Lazy<Transactional<A>>.defer(): Transactional<A> = deferInline { value }

/** TODO */
@ExperimentalFrpApi
fun <A> deferTransactional(block: suspend FrpScope.() -> Transactional<A>): Transactional<A> =
    deferInline {
        NoScope.runInFrpScope(block)
    }

private inline fun <A> deferInline(
    crossinline block: suspend InitScope.() -> Transactional<A>
): Transactional<A> =
    Transactional(TStateInit(init(name = null) { block().impl.init.connect(evalScope = this) }))

/**
 * Returns a [Transactional]. The passed [block] will be evaluated on demand at most once per
 * transaction; any subsequent sampling within the same transaction will receive a cached value.
 */
@ExperimentalFrpApi
fun <A> transactionally(block: suspend FrpTransactionScope.() -> A): Transactional<A> =
    Transactional(tStateOf(transactionalImpl { runInTransactionScope(block) }))
