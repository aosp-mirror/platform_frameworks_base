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

package com.android.systemui.kairos.internal

import com.android.systemui.kairos.internal.util.Key
import com.android.systemui.kairos.internal.util.associateByIndex
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.internal.util.mapValuesParallel
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.none
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal sealed interface TStateImpl<out A> {
    val name: String?
    val operatorName: String
    val changes: TFlowImpl<A>

    suspend fun getCurrentWithEpoch(evalScope: EvalScope): Pair<A, Long>
}

internal sealed class TStateDerived<A>(override val changes: TFlowImpl<A>) :
    TStateImpl<A>, Key<Deferred<Pair<A, Long>>> {

    @Volatile
    var invalidatedEpoch = Long.MIN_VALUE
        private set

    @Volatile
    protected var cache: Any? = EmptyCache
        private set

    override suspend fun getCurrentWithEpoch(evalScope: EvalScope): Pair<A, Long> =
        evalScope.transactionStore
            .getOrPut(this) { evalScope.deferAsync(CoroutineStart.LAZY) { pull(evalScope) } }
            .await()

    suspend fun pull(evalScope: EvalScope): Pair<A, Long> {
        @Suppress("UNCHECKED_CAST")
        return recalc(evalScope)?.also { (a, epoch) -> setCache(a, epoch) }
            ?: ((cache as A) to invalidatedEpoch)
    }

    fun setCache(value: A, epoch: Long) {
        if (epoch > invalidatedEpoch) {
            cache = value
            invalidatedEpoch = epoch
        }
    }

    fun getCachedUnsafe(): Maybe<A> {
        @Suppress("UNCHECKED_CAST")
        return if (cache == EmptyCache) none else just(cache as A)
    }

    protected abstract suspend fun recalc(evalScope: EvalScope): Pair<A, Long>?

    private data object EmptyCache
}

internal class TStateSource<A>(
    override val name: String?,
    override val operatorName: String,
    init: Deferred<A>,
    override val changes: TFlowImpl<A>,
) : TStateImpl<A> {
    constructor(
        name: String?,
        operatorName: String,
        init: A,
        changes: TFlowImpl<A>,
    ) : this(name, operatorName, CompletableDeferred(init), changes)

    lateinit var upstreamConnection: NodeConnection<A>

    // Note: Don't need to synchronize; we will never interleave reads and writes, since all writes
    // are performed at the end of a network step, after any reads would have taken place.

    @Volatile private var _current: Deferred<A> = init
    @Volatile
    var writeEpoch = 0L
        private set

    override suspend fun getCurrentWithEpoch(evalScope: EvalScope): Pair<A, Long> =
        _current.await() to writeEpoch

    /** called by network after eval phase has completed */
    suspend fun updateState(evalScope: EvalScope) {
        // write the latch
        val eventResult = upstreamConnection.getPushEvent(evalScope)
        if (eventResult is Just) {
            _current = CompletableDeferred(eventResult.value)
            writeEpoch = evalScope.epoch
        }
    }

    override fun toString(): String = "TStateImpl(changes=$changes, current=$_current)"

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getStorageUnsafe(): Maybe<A> =
        if (_current.isCompleted) just(_current.getCompleted()) else none
}

internal fun <A> constS(name: String?, operatorName: String, init: A): TStateImpl<A> =
    TStateSource(name, operatorName, init, neverImpl)

internal inline fun <A> mkState(
    name: String?,
    operatorName: String,
    evalScope: EvalScope,
    crossinline getChanges: suspend EvalScope.() -> TFlowImpl<A>,
    init: Deferred<A>,
): TStateImpl<A> {
    lateinit var state: TStateSource<A>
    val calm: TFlowImpl<A> =
        filterNode(getChanges) { new -> new != state.getCurrentWithEpoch(evalScope = this).first }
            .cached()
    return TStateSource(name, operatorName, init, calm).also {
        state = it
        evalScope.scheduleOutput(
            OneShot {
                calm.activate(evalScope = this, downstream = Schedulable.S(state))?.let {
                    (connection, needsEval) ->
                    state.upstreamConnection = connection
                    if (needsEval) {
                        schedule(state)
                    }
                }
            }
        )
    }
}

private inline fun <A> TFlowImpl<A>.calm(
    crossinline getState: () -> TStateDerived<A>
): TFlowImpl<A> =
    filterNode({ this@calm }) { new ->
            val state = getState()
            val (current, _) = state.getCurrentWithEpoch(evalScope = this)
            if (new != current) {
                state.setCache(new, epoch)
                true
            } else {
                false
            }
        }
        .cached()

internal fun <A, B> TStateImpl<A>.mapCheap(
    name: String?,
    operatorName: String,
    transform: suspend EvalScope.(A) -> B,
): TStateImpl<B> =
    DerivedMapCheap(name, operatorName, this, mapImpl({ changes }) { transform(it) }, transform)

internal class DerivedMapCheap<A, B>(
    override val name: String?,
    override val operatorName: String,
    val upstream: TStateImpl<A>,
    override val changes: TFlowImpl<B>,
    private val transform: suspend EvalScope.(A) -> B,
) : TStateImpl<B> {

    override suspend fun getCurrentWithEpoch(evalScope: EvalScope): Pair<B, Long> {
        val (a, epoch) = upstream.getCurrentWithEpoch(evalScope)
        return evalScope.transform(a) to epoch
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal fun <A, B> TStateImpl<A>.map(
    name: String?,
    operatorName: String,
    transform: suspend EvalScope.(A) -> B,
): TStateImpl<B> {
    lateinit var state: TStateDerived<B>
    val mappedChanges = mapImpl({ changes }) { transform(it) }.cached().calm { state }
    state = DerivedMap(name, operatorName, transform, this, mappedChanges)
    return state
}

internal class DerivedMap<A, B>(
    override val name: String?,
    override val operatorName: String,
    private val transform: suspend EvalScope.(A) -> B,
    val upstream: TStateImpl<A>,
    changes: TFlowImpl<B>,
) : TStateDerived<B>(changes) {
    override fun toString(): String = "${this::class.simpleName}@$hashString"

    override suspend fun recalc(evalScope: EvalScope): Pair<B, Long>? {
        val (a, epoch) = upstream.getCurrentWithEpoch(evalScope)
        return if (epoch > invalidatedEpoch) {
            evalScope.transform(a) to epoch
        } else {
            null
        }
    }
}

internal fun <A> TStateImpl<TStateImpl<A>>.flatten(name: String?, operator: String): TStateImpl<A> {
    // emits the current value of the new inner state, when that state is emitted
    val switchEvents = mapImpl({ changes }) { newInner -> newInner.getCurrentWithEpoch(this).first }
    // emits the new value of the new inner state when that state is emitted, or
    // falls back to the current value if a new state is *not* being emitted this
    // transaction
    val innerChanges =
        mapImpl({ changes }) { newInner ->
            mergeNodes({ switchEvents }, { newInner.changes }) { _, new -> new }
        }
    val switchedChanges: TFlowImpl<A> =
        mapImpl({
            switchPromptImpl(
                getStorage = {
                    mapOf(Unit to this@flatten.getCurrentWithEpoch(evalScope = this).first.changes)
                },
                getPatches = { mapImpl({ innerChanges }) { new -> mapOf(Unit to just(new)) } },
            )
        }) { map ->
            map.getValue(Unit)
        }
    lateinit var state: DerivedFlatten<A>
    state = DerivedFlatten(name, operator, this, switchedChanges.calm { state })
    return state
}

internal class DerivedFlatten<A>(
    override val name: String?,
    override val operatorName: String,
    val upstream: TStateImpl<TStateImpl<A>>,
    changes: TFlowImpl<A>,
) : TStateDerived<A>(changes) {
    override suspend fun recalc(evalScope: EvalScope): Pair<A, Long> {
        val (inner, epoch0) = upstream.getCurrentWithEpoch(evalScope)
        val (a, epoch1) = inner.getCurrentWithEpoch(evalScope)
        return a to maxOf(epoch0, epoch1)
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <A, B> TStateImpl<A>.flatMap(
    name: String?,
    operatorName: String,
    noinline transform: suspend EvalScope.(A) -> TStateImpl<B>,
): TStateImpl<B> = map(null, operatorName, transform).flatten(name, operatorName)

internal fun <A, B, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: TStateImpl<A>,
    l2: TStateImpl<B>,
    transform: suspend EvalScope.(A, B) -> Z,
): TStateImpl<Z> =
    zipStates(null, operatorName, mapOf(0 to l1, 1 to l2)).map(name, operatorName) {
        val a = it.getValue(0)
        val b = it.getValue(1)
        @Suppress("UNCHECKED_CAST") transform(a as A, b as B)
    }

internal fun <A, B, C, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: TStateImpl<A>,
    l2: TStateImpl<B>,
    l3: TStateImpl<C>,
    transform: suspend EvalScope.(A, B, C) -> Z,
): TStateImpl<Z> =
    zipStates(null, operatorName, mapOf(0 to l1, 1 to l2, 2 to l3)).map(name, operatorName) {
        val a = it.getValue(0)
        val b = it.getValue(1)
        val c = it.getValue(2)
        @Suppress("UNCHECKED_CAST") transform(a as A, b as B, c as C)
    }

internal fun <A, B, C, D, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: TStateImpl<A>,
    l2: TStateImpl<B>,
    l3: TStateImpl<C>,
    l4: TStateImpl<D>,
    transform: suspend EvalScope.(A, B, C, D) -> Z,
): TStateImpl<Z> =
    zipStates(null, operatorName, mapOf(0 to l1, 1 to l2, 2 to l3, 3 to l4)).map(
        name,
        operatorName,
    ) {
        val a = it.getValue(0)
        val b = it.getValue(1)
        val c = it.getValue(2)
        val d = it.getValue(3)
        @Suppress("UNCHECKED_CAST") transform(a as A, b as B, c as C, d as D)
    }

internal fun <A, B, C, D, E, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: TStateImpl<A>,
    l2: TStateImpl<B>,
    l3: TStateImpl<C>,
    l4: TStateImpl<D>,
    l5: TStateImpl<E>,
    transform: suspend EvalScope.(A, B, C, D, E) -> Z,
): TStateImpl<Z> =
    zipStates(null, operatorName, mapOf(0 to l1, 1 to l2, 2 to l3, 3 to l4, 4 to l5)).map(
        name,
        operatorName,
    ) {
        val a = it.getValue(0)
        val b = it.getValue(1)
        val c = it.getValue(2)
        val d = it.getValue(3)
        val e = it.getValue(4)
        @Suppress("UNCHECKED_CAST") transform(a as A, b as B, c as C, d as D, e as E)
    }

internal fun <K : Any, A> zipStates(
    name: String?,
    operatorName: String,
    states: Map<K, TStateImpl<A>>,
): TStateImpl<Map<K, A>> {
    if (states.isEmpty()) return constS(name, operatorName, emptyMap())
    val stateChanges: Map<K, TFlowImpl<A>> = states.mapValues { it.value.changes }
    lateinit var state: DerivedZipped<K, A>
    // No need for calm; invariant ensures that changes will only emit when there's a difference
    val changes: TFlowImpl<Map<K, A>> =
        mapImpl({
            switchDeferredImpl(getStorage = { stateChanges }, getPatches = { neverImpl })
        }) { patch ->
            states
                .mapValues { (k, v) ->
                    if (k in patch) {
                        patch.getValue(k)
                    } else {
                        v.getCurrentWithEpoch(evalScope = this).first
                    }
                }
                .also { state.setCache(it, epoch) }
        }
    state = DerivedZipped(name, operatorName, states, changes)
    return state
}

internal class DerivedZipped<K : Any, A>(
    override val name: String?,
    override val operatorName: String,
    val upstream: Map<K, TStateImpl<A>>,
    changes: TFlowImpl<Map<K, A>>,
) : TStateDerived<Map<K, A>>(changes) {
    override suspend fun recalc(evalScope: EvalScope): Pair<Map<K, A>, Long> {
        val newEpoch = AtomicLong()
        return upstream.mapValuesParallel {
            val (a, epoch) = it.value.getCurrentWithEpoch(evalScope)
            newEpoch.accumulateAndGet(epoch, ::maxOf)
            a
        } to newEpoch.get()
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <A> zipStates(
    name: String?,
    operatorName: String,
    states: List<TStateImpl<A>>,
): TStateImpl<List<A>> =
    if (states.isEmpty()) {
        constS(name, operatorName, emptyList())
    } else {
        zipStates(null, operatorName, states.asIterable().associateByIndex()).mapCheap(
            name,
            operatorName,
        ) {
            it.values.toList()
        }
    }
