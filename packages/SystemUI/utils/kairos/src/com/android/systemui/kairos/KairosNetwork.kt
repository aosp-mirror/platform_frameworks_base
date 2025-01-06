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

import com.android.systemui.kairos.internal.BuildScopeImpl
import com.android.systemui.kairos.internal.Network
import com.android.systemui.kairos.internal.StateScopeImpl
import com.android.systemui.kairos.internal.util.awaitCancellationAndThen
import com.android.systemui.kairos.internal.util.childScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Marks declarations that are still **experimental** and shouldn't be used in general production
 * code.
 */
@RequiresOptIn(
    message = "This API is experimental and should not be used in general production code."
)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalKairosApi

/**
 * External interface to a Kairos network of reactive components. Can be used to make transactional
 * queries and modifications to the network.
 */
@ExperimentalKairosApi
interface KairosNetwork {
    /**
     * Runs [block] inside of a transaction, suspending until the transaction is complete.
     *
     * The [BuildScope] receiver exposes methods that can be used to query or modify the network. If
     * the network is cancelled while the caller of [transact] is suspended, then the call will be
     * cancelled.
     */
    suspend fun <R> transact(block: TransactionScope.() -> R): R

    /**
     * Activates [spec] in a transaction, suspending indefinitely. While suspended, all observers
     * and long-running effects are kept alive. When cancelled, observers are unregistered and
     * effects are cancelled.
     */
    suspend fun activateSpec(spec: BuildSpec<*>)

    /** Returns a [CoalescingMutableEvents] that can emit values into this [KairosNetwork]. */
    fun <In, Out> coalescingMutableEvents(
        coalesce: (old: Out, new: In) -> Out,
        getInitialValue: () -> Out,
    ): CoalescingMutableEvents<In, Out>

    /** Returns a [MutableState] that can emit values into this [KairosNetwork]. */
    fun <T> mutableEvents(): MutableEvents<T>

    /** Returns a [CoalescingMutableEvents] that can emit values into this [KairosNetwork]. */
    fun <T> conflatedMutableEvents(): CoalescingMutableEvents<T, T>

    /** Returns a [MutableState]. with initial state [initialValue]. */
    fun <T> mutableStateDeferred(initialValue: DeferredValue<T>): MutableState<T>
}

/** Returns a [CoalescingMutableEvents] that can emit values into this [KairosNetwork]. */
@ExperimentalKairosApi
fun <In, Out> KairosNetwork.coalescingMutableEvents(
    coalesce: (old: Out, new: In) -> Out,
    initialValue: Out,
): CoalescingMutableEvents<In, Out> =
    coalescingMutableEvents(coalesce, getInitialValue = { initialValue })

/** Returns a [MutableState] with initial state [initialValue]. */
@ExperimentalKairosApi
fun <T> KairosNetwork.mutableState(initialValue: T): MutableState<T> =
    mutableStateDeferred(deferredOf(initialValue))

/** Returns a [MutableState] with initial state [initialValue]. */
@ExperimentalKairosApi
fun <T> MutableState(network: KairosNetwork, initialValue: T): MutableState<T> =
    network.mutableState(initialValue)

/** Returns a [MutableEvents] that can emit values into this [KairosNetwork]. */
@ExperimentalKairosApi
fun <T> MutableEvents(network: KairosNetwork): MutableEvents<T> = network.mutableEvents()

/** Returns a [CoalescingMutableEvents] that can emit values into this [KairosNetwork]. */
@ExperimentalKairosApi
fun <In, Out> CoalescingMutableEvents(
    network: KairosNetwork,
    coalesce: (old: Out, new: In) -> Out,
    initialValue: Out,
): CoalescingMutableEvents<In, Out> = network.coalescingMutableEvents(coalesce) { initialValue }

/** Returns a [CoalescingMutableEvents] that can emit values into this [KairosNetwork]. */
@ExperimentalKairosApi
fun <In, Out> CoalescingMutableEvents(
    network: KairosNetwork,
    coalesce: (old: Out, new: In) -> Out,
    getInitialValue: () -> Out,
): CoalescingMutableEvents<In, Out> = network.coalescingMutableEvents(coalesce, getInitialValue)

/** Returns a [CoalescingMutableEvents] that can emit values into this [KairosNetwork]. */
@ExperimentalKairosApi
fun <T> ConflatedMutableEvents(network: KairosNetwork): CoalescingMutableEvents<T, T> =
    network.conflatedMutableEvents()

/**
 * Activates [spec] in a transaction and invokes [block] with the result, suspending indefinitely.
 * While suspended, all observers and long-running effects are kept alive. When cancelled, observers
 * are unregistered and effects are cancelled.
 */
@ExperimentalKairosApi
suspend fun <R> KairosNetwork.activateSpec(spec: BuildSpec<R>, block: suspend (R) -> Unit) {
    activateSpec {
        val result = spec.applySpec()
        launchEffect { block(result) }
    }
}

internal class LocalNetwork(
    private val network: Network,
    private val scope: CoroutineScope,
    private val endSignal: Events<Any>,
) : KairosNetwork {
    override suspend fun <R> transact(block: TransactionScope.() -> R): R =
        network.transaction("KairosNetwork.transact") { block() }.await()

    override suspend fun activateSpec(spec: BuildSpec<*>) {
        val stopEmitter =
            CoalescingMutableEvents(
                name = "activateSpec",
                coalesce = { _, _: Unit -> },
                network = network,
                getInitialValue = {},
            )
        val job =
            network
                .transaction("KairosNetwork.activateSpec") {
                    val buildScope =
                        BuildScopeImpl(
                            stateScope =
                                StateScopeImpl(
                                    evalScope = this,
                                    endSignal = mergeLeft(stopEmitter, endSignal),
                                ),
                            coroutineScope = scope,
                        )
                    buildScope.launchScope(spec)
                }
                .await()
        awaitCancellationAndThen {
            stopEmitter.emit(Unit)
            job.cancel()
        }
    }

    override fun <In, Out> coalescingMutableEvents(
        coalesce: (old: Out, new: In) -> Out,
        getInitialValue: () -> Out,
    ): CoalescingMutableEvents<In, Out> =
        CoalescingMutableEvents(
            null,
            coalesce = { old, new -> coalesce(old.value, new) },
            network,
            getInitialValue,
        )

    override fun <T> conflatedMutableEvents(): CoalescingMutableEvents<T, T> =
        CoalescingMutableEvents(
            null,
            coalesce = { _, new -> new },
            network,
            { error("WTF: init value accessed for conflatedMutableEvents") },
        )

    override fun <T> mutableEvents(): MutableEvents<T> = MutableEvents(network)

    override fun <T> mutableStateDeferred(initialValue: DeferredValue<T>): MutableState<T> =
        MutableState(network, initialValue.unwrapped)
}

/**
 * Combination of an [KairosNetwork] and a [Job] that, when cancelled, will cancel the entire Kairos
 * network.
 */
@ExperimentalKairosApi
class RootKairosNetwork
internal constructor(private val network: Network, private val scope: CoroutineScope, job: Job) :
    Job by job, KairosNetwork by LocalNetwork(network, scope, emptyEvents)

/** Constructs a new [RootKairosNetwork] in the given [CoroutineScope]. */
@ExperimentalKairosApi
fun CoroutineScope.launchKairosNetwork(
    context: CoroutineContext = EmptyCoroutineContext
): RootKairosNetwork {
    val scope = childScope(context)
    val network = Network(scope)
    scope.launch(CoroutineName("launchKairosNetwork scheduler")) { network.runInputScheduler() }
    return RootKairosNetwork(network, scope, scope.coroutineContext.job)
}
