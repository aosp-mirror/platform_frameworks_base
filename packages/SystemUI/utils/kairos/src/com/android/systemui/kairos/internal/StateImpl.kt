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

internal open class StateImpl<out A>(
    val name: String?,
    val operatorName: String,
    val changes: EventsImpl<A>,
    val store: StateStore<A>,
) {
    fun getCurrentWithEpoch(evalScope: EvalScope): Pair<A, Long> =
        store.getCurrentWithEpoch(evalScope)
}

internal sealed class StateDerived<A> : StateStore<A>() {

    @Volatile
    var invalidatedEpoch = Long.MIN_VALUE
        private set

    @Volatile
    protected var validatedEpoch = Long.MIN_VALUE
        private set

    @Volatile
    protected var cache: Any? = EmptyCache
        private set

    private val transactionCache = TransactionCache<Lazy<Pair<A, Long>>>()

    override fun getCurrentWithEpoch(evalScope: EvalScope): Pair<A, Long> =
        transactionCache.getOrPut(evalScope) { evalScope.deferAsync { pull(evalScope) } }.value

    fun pull(evalScope: EvalScope): Pair<A, Long> {
        @Suppress("UNCHECKED_CAST")
        val result =
            recalc(evalScope)?.let { (newValue, epoch) ->
                newValue.also {
                    if (epoch > validatedEpoch) {
                        validatedEpoch = epoch
                        if (cache != newValue) {
                            cache = newValue
                            invalidatedEpoch = epoch
                        }
                    }
                }
            } ?: (cache as A)
        return result to invalidatedEpoch
    }

    fun getCachedUnsafe(): Maybe<A> {
        @Suppress("UNCHECKED_CAST")
        return if (cache == EmptyCache) Maybe.absent else Maybe.present(cache as A)
    }

    protected abstract fun recalc(evalScope: EvalScope): Pair<A, Long>?

    fun setCacheFromPush(value: A, epoch: Long) {
        cache = value
        validatedEpoch = epoch + 1
        invalidatedEpoch = epoch + 1
    }

    private data object EmptyCache
}

internal sealed class StateStore<out S> {
    abstract fun getCurrentWithEpoch(evalScope: EvalScope): Pair<S, Long>
}

internal class StateSource<S>(init: Lazy<S>) : StateStore<S>() {
    constructor(init: S) : this(CompletableLazy(init))

    lateinit var upstreamConnection: NodeConnection<S>

    // Note: Don't need to synchronize; we will never interleave reads and writes, since all writes
    // are performed at the end of a network step, after any reads would have taken place.

    @Volatile private var _current: Lazy<S> = init

    @Volatile
    var writeEpoch = 0L
        private set

    override fun getCurrentWithEpoch(evalScope: EvalScope): Pair<S, Long> =
        _current.value to writeEpoch

    /** called by network after eval phase has completed */
    fun updateState(logIndent: Int, evalScope: EvalScope) {
        // write the latch
        _current = CompletableLazy(upstreamConnection.getPushEvent(logIndent, evalScope))
        writeEpoch = evalScope.epoch + 1
    }

    override fun toString(): String = "StateImpl(current=$_current, writeEpoch=$writeEpoch)"

    fun getStorageUnsafe(): Maybe<S> =
        if (_current.isInitialized()) Maybe.present(_current.value) else Maybe.absent
}

internal fun <A> constState(name: String?, operatorName: String, init: A): StateImpl<A> =
    StateImpl(name, operatorName, neverImpl, StateSource(init))

internal inline fun <A> activatedStateSource(
    name: String?,
    operatorName: String,
    evalScope: EvalScope,
    crossinline getChanges: EvalScope.() -> EventsImpl<A>,
    init: Lazy<A>,
): StateImpl<A> {
    val store = StateSource(init)
    val calm: EventsImpl<A> =
        filterImpl(getChanges) { new -> new != store.getCurrentWithEpoch(evalScope = this).first }
    evalScope.scheduleOutput(
        OneShot {
            calm.activate(evalScope = this, downstream = Schedulable.S(store))?.let {
                (connection, needsEval) ->
                store.upstreamConnection = connection
                if (needsEval) {
                    schedule(store)
                }
            }
        }
    )
    return StateImpl(name, operatorName, calm, store)
}

private inline fun <A> EventsImpl<A>.calm(state: StateDerived<A>): EventsImpl<A> =
    filterImpl({ this@calm }) { new ->
            val (current, _) = state.getCurrentWithEpoch(evalScope = this)
            if (new != current) {
                state.setCacheFromPush(new, epoch)
                true
            } else {
                false
            }
        }
        .cached()

internal fun <A, B> mapStateImplCheap(
    stateImpl: Init<StateImpl<A>>,
    name: String?,
    operatorName: String,
    transform: EvalScope.(A) -> B,
): StateImpl<B> =
    StateImpl(
        name = name,
        operatorName = operatorName,
        changes = mapImpl({ stateImpl.connect(this).changes }) { it, _ -> transform(it) },
        store = DerivedMapCheap(stateImpl, transform),
    )

internal class DerivedMapCheap<A, B>(
    val upstream: Init<StateImpl<A>>,
    private val transform: EvalScope.(A) -> B,
) : StateStore<B>() {

    override fun getCurrentWithEpoch(evalScope: EvalScope): Pair<B, Long> {
        val (a, epoch) = upstream.connect(evalScope).getCurrentWithEpoch(evalScope)
        return evalScope.transform(a) to epoch
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal fun <A, B> mapStateImpl(
    stateImpl: InitScope.() -> StateImpl<A>,
    name: String?,
    operatorName: String,
    transform: EvalScope.(A) -> B,
): StateImpl<B> {
    val store = DerivedMap(stateImpl, transform)
    val mappedChanges =
        mapImpl({ stateImpl().changes }) { it, _ -> transform(it) }.cached().calm(store)
    return StateImpl(name, operatorName, mappedChanges, store)
}

internal class DerivedMap<A, B>(
    val upstream: InitScope.() -> StateImpl<A>,
    private val transform: EvalScope.(A) -> B,
) : StateDerived<B>() {
    override fun toString(): String = "${this::class.simpleName}@$hashString"

    override fun recalc(evalScope: EvalScope): Pair<B, Long>? {
        val (a, epoch) = evalScope.upstream().getCurrentWithEpoch(evalScope)
        return if (epoch > validatedEpoch) {
            evalScope.transform(a) to epoch
        } else {
            null
        }
    }
}

internal fun <A> flattenStateImpl(
    stateImpl: InitScope.() -> StateImpl<StateImpl<A>>,
    name: String?,
    operator: String,
): StateImpl<A> {
    // emits the current value of the new inner state, when that state is emitted
    val switchEvents =
        mapImpl({ stateImpl().changes }) { newInner, _ -> newInner.getCurrentWithEpoch(this).first }
    // emits the new value of the new inner state when that state is emitted, or
    // falls back to the current value if a new state is *not* being emitted this
    // transaction
    val innerChanges =
        mapImpl({ stateImpl().changes }) { newInner, _ ->
            mergeNodes({ switchEvents }, { newInner.changes }) { _, new -> new }
        }
    val switchedChanges: EventsImpl<A> =
        switchPromptImplSingle(
            getStorage = { stateImpl().getCurrentWithEpoch(evalScope = this).first.changes },
            getPatches = { innerChanges },
        )
    val store: DerivedFlatten<A> = DerivedFlatten(stateImpl)
    return StateImpl(name, operator, switchedChanges.calm(store), store)
}

internal class DerivedFlatten<A>(val upstream: InitScope.() -> StateImpl<StateImpl<A>>) :
    StateDerived<A>() {
    override fun recalc(evalScope: EvalScope): Pair<A, Long> {
        val (inner, epoch0) = evalScope.upstream().getCurrentWithEpoch(evalScope)
        val (a, epoch1) = inner.getCurrentWithEpoch(evalScope)
        return a to maxOf(epoch0, epoch1)
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <A, B> flatMapStateImpl(
    noinline stateImpl: InitScope.() -> StateImpl<A>,
    name: String?,
    operatorName: String,
    noinline transform: EvalScope.(A) -> StateImpl<B>,
): StateImpl<B> {
    val mapped = mapStateImpl(stateImpl, null, operatorName, transform)
    return flattenStateImpl({ mapped }, name, operatorName)
}

internal fun <A, B, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: Init<StateImpl<A>>,
    l2: Init<StateImpl<B>>,
    transform: EvalScope.(A, B) -> Z,
): StateImpl<Z> {
    val zipped =
        zipStateList(
            null,
            operatorName,
            2,
            init(null) { listOf(l1.connect(this), l2.connect(this)) },
        )
    return mapStateImpl({ zipped }, name, operatorName) {
        @Suppress("UNCHECKED_CAST") transform(it[0] as A, it[1] as B)
    }
}

internal fun <A, B, C, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: Init<StateImpl<A>>,
    l2: Init<StateImpl<B>>,
    l3: Init<StateImpl<C>>,
    transform: EvalScope.(A, B, C) -> Z,
): StateImpl<Z> {
    val zipped =
        zipStateList(
            null,
            operatorName,
            3,
            init(null) { listOf(l1.connect(this), l2.connect(this), l3.connect(this)) },
        )
    return mapStateImpl({ zipped }, name, operatorName) {
        @Suppress("UNCHECKED_CAST") transform(it[0] as A, it[1] as B, it[2] as C)
    }
}

internal fun <A, B, C, D, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: Init<StateImpl<A>>,
    l2: Init<StateImpl<B>>,
    l3: Init<StateImpl<C>>,
    l4: Init<StateImpl<D>>,
    transform: EvalScope.(A, B, C, D) -> Z,
): StateImpl<Z> {
    val zipped =
        zipStateList(
            null,
            operatorName,
            4,
            init(null) {
                listOf(l1.connect(this), l2.connect(this), l3.connect(this), l4.connect(this))
            },
        )
    return mapStateImpl({ zipped }, name, operatorName) {
        @Suppress("UNCHECKED_CAST") transform(it[0] as A, it[1] as B, it[2] as C, it[3] as D)
    }
}

internal fun <A, B, C, D, E, Z> zipStates(
    name: String?,
    operatorName: String,
    l1: Init<StateImpl<A>>,
    l2: Init<StateImpl<B>>,
    l3: Init<StateImpl<C>>,
    l4: Init<StateImpl<D>>,
    l5: Init<StateImpl<E>>,
    transform: EvalScope.(A, B, C, D, E) -> Z,
): StateImpl<Z> {
    val zipped =
        zipStateList(
            null,
            operatorName,
            5,
            init(null) {
                listOf(
                    l1.connect(this),
                    l2.connect(this),
                    l3.connect(this),
                    l4.connect(this),
                    l5.connect(this),
                )
            },
        )
    return mapStateImpl({ zipped }, name, operatorName) {
        @Suppress("UNCHECKED_CAST")
        transform(it[0] as A, it[1] as B, it[2] as C, it[3] as D, it[4] as E)
    }
}

internal fun <K, V> zipStateMap(
    name: String?,
    operatorName: String,
    numStates: Int,
    states: Init<Map<K, StateImpl<V>>>,
): StateImpl<Map<K, V>> =
    zipStates(
        name = name,
        operatorName = operatorName,
        numStates = numStates,
        states = init(null) { states.connect(this).asIterable() },
        storeFactory = ConcurrentHashMapK.Factory(),
    )

internal fun <V> zipStateList(
    name: String?,
    operatorName: String,
    numStates: Int,
    states: Init<List<StateImpl<V>>>,
): StateImpl<List<V>> {
    val zipped =
        zipStates(
            name = name,
            operatorName = operatorName,
            numStates = numStates,
            states = init(name) { states.connect(this).asIterableWithIndex() },
            storeFactory = MutableArrayMapK.Factory(),
        )
    // Like mapCheap, but with caching (or like map, but without the calm changes, as they are not
    // necessary).
    return StateImpl(
        name = name,
        operatorName = operatorName,
        changes = mapImpl({ zipped.changes }) { arrayStore, _ -> arrayStore.values.toList() },
        DerivedMap(upstream = { zipped }, transform = { arrayStore -> arrayStore.values.toList() }),
    )
}

internal fun <W, K, A> zipStates(
    name: String?,
    operatorName: String,
    numStates: Int,
    states: Init<Iterable<Map.Entry<K, StateImpl<A>>>>,
    storeFactory: MutableMapK.Factory<W, K>,
): StateImpl<MutableMapK<W, K, A>> {
    if (numStates == 0) {
        return constState(name, operatorName, storeFactory.create(0))
    }
    val stateStore = DerivedZipped(numStates, states, storeFactory)
    // No need for calm; invariant ensures that changes will only emit when there's a difference
    val switchDeferredImpl =
        switchDeferredImpl(
            getStorage = {
                states
                    .connect(this)
                    .asSequence()
                    .map { (k, v) -> StoreEntry(k, v.changes) }
                    .asIterable()
            },
            getPatches = { neverImpl },
            storeFactory = storeFactory,
        )
    val changes =
        mapImpl({ switchDeferredImpl }) { patch, logIndent ->
                val muxStore = storeFactory.create<A>(numStates)
                states.connect(this).forEach { (k, state) ->
                    muxStore[k] =
                        if (patch.contains(k)) {
                            patch.getValue(k).getPushEvent(logIndent, evalScope = this@mapImpl)
                        } else {
                            state.getCurrentWithEpoch(evalScope = this@mapImpl).first
                        }
                }
                // Read the current value so that it is cached in this transaction and won't be
                // clobbered by the cache write
                stateStore.getCurrentWithEpoch(evalScope = this)
                muxStore.also { stateStore.setCacheFromPush(it, epoch) }
            }
            .cached()
    return StateImpl(name, operatorName, changes, stateStore)
}

internal class DerivedZipped<W, K, A>(
    private val upstreamSize: Int,
    val upstream: Init<Iterable<Map.Entry<K, StateImpl<A>>>>,
    private val storeFactory: MutableMapK.Factory<W, K>,
) : StateDerived<MutableMapK<W, K, A>>() {
    override fun recalc(evalScope: EvalScope): Pair<MutableMapK<W, K, A>, Long> {
        var newEpoch = 0L
        val store = storeFactory.create<A>(upstreamSize)
        for ((key, value) in upstream.connect(evalScope)) {
            val (a, epoch) = value.getCurrentWithEpoch(evalScope)
            newEpoch = maxOf(newEpoch, epoch)
            store[key] = a
        }
        return store to newEpoch
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <A> zipStates(
    name: String?,
    operatorName: String,
    numStates: Int,
    states: Init<List<StateImpl<A>>>,
): StateImpl<List<A>> =
    if (numStates <= 0) {
        constState(name, operatorName, emptyList())
    } else {
        zipStateList(null, operatorName, numStates, states)
    }
