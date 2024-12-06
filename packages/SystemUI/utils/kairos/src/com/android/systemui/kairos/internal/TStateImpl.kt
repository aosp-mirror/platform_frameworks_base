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

import com.android.systemui.kairos.internal.store.ConcurrentHashMapK
import com.android.systemui.kairos.internal.store.MutableArrayMapK
import com.android.systemui.kairos.internal.store.MutableMapK
import com.android.systemui.kairos.internal.store.StoreEntry
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.none
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal sealed interface TStateImpl<out A> {
    val name: String?
    val operatorName: String
    val changes: TFlowImpl<A>

    fun getCurrentWithEpoch(evalScope: EvalScope): Pair<A, Long>
}

internal sealed class TStateDerived<A>(override val changes: TFlowImpl<A>) : TStateImpl<A> {

    @Volatile
    var invalidatedEpoch = Long.MIN_VALUE
        private set

    @Volatile
    protected var cache: Any? = EmptyCache
        private set

    private val transactionCache = TransactionCache<Lazy<Pair<A, Long>>>()

    override fun getCurrentWithEpoch(evalScope: EvalScope): Pair<A, Long> =
        transactionCache.getOrPut(evalScope) { evalScope.deferAsync { pull(evalScope) } }.value

    fun pull(evalScope: EvalScope): Pair<A, Long> {
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

    protected abstract fun recalc(evalScope: EvalScope): Pair<A, Long>?

    private data object EmptyCache
}

internal class TStateSource<A>(
    override val name: String?,
    override val operatorName: String,
    init: Lazy<A>,
    override val changes: TFlowImpl<A>,
) : TStateImpl<A> {
    constructor(
        name: String?,
        operatorName: String,
        init: A,
        changes: TFlowImpl<A>,
    ) : this(name, operatorName, CompletableLazy(init), changes)

    lateinit var upstreamConnection: NodeConnection<A>

    // Note: Don't need to synchronize; we will never interleave reads and writes, since all writes
    // are performed at the end of a network step, after any reads would have taken place.

    @Volatile private var _current: Lazy<A> = init

    @Volatile
    var writeEpoch = 0L
        private set

    override fun getCurrentWithEpoch(evalScope: EvalScope): Pair<A, Long> =
        _current.value to writeEpoch

    /** called by network after eval phase has completed */
    fun updateState(logIndent: Int, evalScope: EvalScope) {
        // write the latch
        // TODO: deferAsync?
        _current = CompletableLazy(upstreamConnection.getPushEvent(logIndent, evalScope))
        writeEpoch = evalScope.epoch
    }

    override fun toString(): String = "TStateImpl(changes=$changes, current=$_current)"

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getStorageUnsafe(): Maybe<A> = if (_current.isInitialized()) just(_current.value) else none
}

internal fun <A> constS(name: String?, operatorName: String, init: A): TStateImpl<A> =
    TStateSource(name, operatorName, init, neverImpl)

internal inline fun <A> activatedTStateSource(
    name: String?,
    operatorName: String,
    evalScope: EvalScope,
    crossinline getChanges: EvalScope.() -> TFlowImpl<A>,
    init: Lazy<A>,
): TStateImpl<A> {
    lateinit var state: TStateSource<A>
    val calm: TFlowImpl<A> =
        filterImpl(getChanges) { new -> new != state.getCurrentWithEpoch(evalScope = this).first }
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
    filterImpl({ this@calm }) { new ->
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
    transform: EvalScope.(A) -> B,
): TStateImpl<B> =
    DerivedMapCheap(
        name,
        operatorName,
        this,
        mapImpl({ changes }) { it, _ -> transform(it) },
        transform,
    )

internal class DerivedMapCheap<A, B>(
    override val name: String?,
    override val operatorName: String,
    val upstream: TStateImpl<A>,
    override val changes: TFlowImpl<B>,
    private val transform: EvalScope.(A) -> B,
) : TStateImpl<B> {

    override fun getCurrentWithEpoch(evalScope: EvalScope): Pair<B, Long> {
        val (a, epoch) = upstream.getCurrentWithEpoch(evalScope)
        return evalScope.transform(a) to epoch
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal fun <A, B> TStateImpl<A>.map(
    name: String?,
    operatorName: String,
    transform: EvalScope.(A) -> B,
): TStateImpl<B> {
    lateinit var state: TStateDerived<B>
    val mappedChanges = mapImpl({ changes }) { it, _ -> transform(it) }.cached().calm { state }
    state = DerivedMap(name, operatorName, transform, this, mappedChanges)
    return state
}

internal class DerivedMap<A, B>(
    override val name: String?,
    override val operatorName: String,
    private val transform: EvalScope.(A) -> B,
    val upstream: TStateImpl<A>,
    changes: TFlowImpl<B>,
) : TStateDerived<B>(changes) {
    override fun toString(): String = "${this::class.simpleName}@$hashString"

    override fun recalc(evalScope: EvalScope): Pair<B, Long>? {
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
    val switchEvents =
        mapImpl({ changes }) { newInner, _ -> newInner.getCurrentWithEpoch(this).first }
    // emits the new value of the new inner state when that state is emitted, or
    // falls back to the current value if a new state is *not* being emitted this
    // transaction
    val innerChanges =
        mapImpl({ changes }) { newInner, _ ->
            mergeNodes({ switchEvents }, { newInner.changes }) { _, new -> new }
        }
    val switchedChanges: TFlowImpl<A> =
        switchPromptImplSingle(
            getStorage = { this@flatten.getCurrentWithEpoch(evalScope = this).first.changes },
            getPatches = { innerChanges },
        )
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
    override fun recalc(evalScope: EvalScope): Pair<A, Long> {
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
    noinline transform: EvalScope.(A) -> TStateImpl<B>,
): TStateImpl<B> = map(null, operatorName, transform).flatten(name, operatorName)

internal fun <A, B, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: TStateImpl<A>,
    l2: TStateImpl<B>,
    transform: EvalScope.(A, B) -> Z,
): TStateImpl<Z> =
    zipStateList(null, operatorName, listOf(l1, l2)).map(name, operatorName) {
        @Suppress("UNCHECKED_CAST") transform(it[0] as A, it[1] as B)
    }

internal fun <A, B, C, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: TStateImpl<A>,
    l2: TStateImpl<B>,
    l3: TStateImpl<C>,
    transform: EvalScope.(A, B, C) -> Z,
): TStateImpl<Z> =
    zipStateList(null, operatorName, listOf(l1, l2, l3)).map(name, operatorName) {
        @Suppress("UNCHECKED_CAST") transform(it[0] as A, it[1] as B, it[2] as C)
    }

internal fun <A, B, C, D, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: TStateImpl<A>,
    l2: TStateImpl<B>,
    l3: TStateImpl<C>,
    l4: TStateImpl<D>,
    transform: EvalScope.(A, B, C, D) -> Z,
): TStateImpl<Z> =
    zipStateList(null, operatorName, listOf(l1, l2, l3, l4)).map(name, operatorName) {
        @Suppress("UNCHECKED_CAST") transform(it[0] as A, it[1] as B, it[2] as C, it[3] as D)
    }

internal fun <A, B, C, D, E, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: TStateImpl<A>,
    l2: TStateImpl<B>,
    l3: TStateImpl<C>,
    l4: TStateImpl<D>,
    l5: TStateImpl<E>,
    transform: EvalScope.(A, B, C, D, E) -> Z,
): TStateImpl<Z> =
    zipStateList(null, operatorName, listOf(l1, l2, l3, l4, l5)).map(name, operatorName) {
        @Suppress("UNCHECKED_CAST")
        transform(it[0] as A, it[1] as B, it[2] as C, it[3] as D, it[4] as E)
    }

internal fun <K, V> zipStateMap(
    name: String?,
    operatorName: String,
    states: Map<K, TStateImpl<V>>,
): TStateImpl<Map<K, V>> =
    zipStates(
        name = name,
        operatorName = operatorName,
        numStates = states.size,
        states = states.asIterable(),
        storeFactory = ConcurrentHashMapK.Factory(),
    )

internal fun <V> zipStateList(
    name: String?,
    operatorName: String,
    states: List<TStateImpl<V>>,
): TStateImpl<List<V>> {
    val zipped =
        zipStates(
            name = name,
            operatorName = operatorName,
            numStates = states.size,
            states = states.asIterableWithIndex(),
            storeFactory = MutableArrayMapK.Factory(),
        )
    // Like mapCheap, but with caching (or like map, but without the calm changes, as they are not
    // necessary).
    return DerivedMap(
        name = name,
        operatorName = operatorName,
        transform = { arrayStore -> arrayStore.values.toList() },
        upstream = zipped,
        changes = mapImpl({ zipped.changes }) { arrayStore, _ -> arrayStore.values.toList() },
    )
}

internal fun <W, K, A> zipStates(
    name: String?,
    operatorName: String,
    numStates: Int,
    states: Iterable<Map.Entry<K, TStateImpl<A>>>,
    storeFactory: MutableMapK.Factory<W, K>,
): TStateImpl<MutableMapK<W, K, A>> {
    if (numStates == 0) {
        return constS(name, operatorName, storeFactory.create(0))
    }
    val stateChanges = states.asSequence().map { (k, v) -> StoreEntry(k, v.changes) }.asIterable()
    lateinit var state: DerivedZipped<W, K, A>
    // No need for calm; invariant ensures that changes will only emit when there's a difference
    val switchDeferredImpl =
        switchDeferredImpl(
            getStorage = { stateChanges },
            getPatches = { neverImpl },
            storeFactory = storeFactory,
        )
    val changes =
        mapImpl({ switchDeferredImpl }) { patch, logIndent ->
                val store = storeFactory.create<A>(numStates)
                states.forEach { (k, state) ->
                    store[k] =
                        if (patch.contains(k)) {
                            patch.getValue(k).getPushEvent(logIndent, evalScope = this@mapImpl)
                        } else {
                            state.getCurrentWithEpoch(evalScope = this@mapImpl).first
                        }
                }
                store.also { state.setCache(it, epoch) }
            }
            .cached()
    state =
        DerivedZipped(
            name = name,
            operatorName = operatorName,
            upstreamSize = numStates,
            upstream = states,
            changes = changes,
            storeFactory = storeFactory,
        )
    return state
}

internal class DerivedZipped<W, K, A>(
    override val name: String?,
    override val operatorName: String,
    private val upstreamSize: Int,
    val upstream: Iterable<Map.Entry<K, TStateImpl<A>>>,
    changes: TFlowImpl<MutableMapK<W, K, A>>,
    private val storeFactory: MutableMapK.Factory<W, K>,
) : TStateDerived<MutableMapK<W, K, A>>(changes) {
    override fun recalc(evalScope: EvalScope): Pair<MutableMapK<W, K, A>, Long> {
        val newEpoch = AtomicLong()
        val store = storeFactory.create<A>(upstreamSize)
        for ((key, value) in upstream) {
            val (a, epoch) = value.getCurrentWithEpoch(evalScope)
            newEpoch.accumulateAndGet(epoch, ::maxOf)
            store[key] = a
        }
        return store to newEpoch.get()
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
        zipStateList(null, operatorName, states)
    }
