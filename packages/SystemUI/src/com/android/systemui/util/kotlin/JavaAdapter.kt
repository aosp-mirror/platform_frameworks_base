/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.android.systemui.util.kotlin

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.repeatWhenAttached
import java.util.function.Consumer
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A class allowing Java classes to collect on Kotlin flows. */
@SysUISingleton
class JavaAdapter @Inject constructor(@Application private val scope: CoroutineScope) {
    /**
     * Collect information for the given [flow], calling [consumer] for each emitted event.
     *
     * Important: This will immediately start collection and *never* stop it. This should only be
     * used by classes that *need* to always be collecting a value and processing it. Whenever
     * possible, please use [collectFlow] instead; that method will stop the collection when a view
     * has disappeared, which will ensure that we don't perform unnecessary work.
     *
     * Do *not* call this method in a class's constructor. Instead, call it in
     * [com.android.systemui.CoreStartable.start] or similar method.
     */
    fun <T> alwaysCollectFlow(flow: Flow<T>, consumer: Consumer<T>): Job {
        return scope.launch { flow.collect { consumer.accept(it) } }
    }

    @JvmOverloads
    fun <T> stateInApp(
        flow: Flow<T>,
        initialValue: T,
        started: SharingStarted = SharingStarted.Eagerly,
    ): StateFlow<T> {
        return flow.stateIn(scope, started, initialValue)
    }
}

/**
 * Collect information for the given [flow], calling [consumer] for each emitted event. Defaults to
 * [LifeCycle.State.CREATED] to better align with legacy ViewController usage of attaching listeners
 * during onViewAttached() and removing during onViewRemoved().
 *
 * @return a disposable handle in order to cancel the flow in the future.
 */
@JvmOverloads
fun <T> collectFlow(
    view: View,
    flow: Flow<T>,
    consumer: Consumer<T>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    state: Lifecycle.State = Lifecycle.State.CREATED,
): DisposableHandle {
    return view.repeatWhenAttached(coroutineContext) {
        repeatOnLifecycle(state) { flow.collect { consumer.accept(it) } }
    }
}

/**
 * Collect information for the given [flow], calling [consumer] for each emitted event. Defaults to
 * [LifeCycle.State.CREATED] which is mapped over from the equivalent definition for collecting the
 * flow on a view.
 */
@JvmOverloads
fun <T> collectFlow(
    lifecycle: Lifecycle,
    flow: Flow<T>,
    consumer: Consumer<T>,
    state: Lifecycle.State = Lifecycle.State.CREATED,
): Job {
    return lifecycle.coroutineScope.launch {
        lifecycle.repeatOnLifecycle(state) { flow.collect { consumer.accept(it) } }
    }
}

fun <A, B, R> combineFlows(flow1: Flow<A>, flow2: Flow<B>, bifunction: (A, B) -> R): Flow<R> {
    return combine(flow1, flow2, bifunction)
}

fun <A, B, C, R> combineFlows(
    flow1: Flow<A>,
    flow2: Flow<B>,
    flow3: Flow<C>,
    trifunction: (A, B, C) -> R,
): Flow<R> {
    return combine(flow1, flow2, flow3, trifunction)
}

fun <T1, T2, T3, T4, R> combineFlows(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    transform: (T1, T2, T3, T4) -> R,
): Flow<R> {
    return combine(flow, flow2, flow3, flow4, transform)
}

fun <T1, T2, T3, T4, T5, R> combineFlows(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    transform: (T1, T2, T3, T4, T5) -> R,
): Flow<R> {
    return combine(flow, flow2, flow3, flow4, flow5, transform)
}

fun <T1, T2, T3, T4, T5, T6, R> combineFlows(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    transform: (T1, T2, T3, T4, T5, T6) -> R,
): Flow<R> {
    return combine(flow, flow2, flow3, flow4, flow5, flow6, transform)
}
