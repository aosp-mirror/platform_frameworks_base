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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.kairos.internal.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.newCoroutineContext

private const val LogEnabled = false

@Suppress("NOTHING_TO_INLINE")
internal inline fun logLn(indent: Int = 0, message: Any?) {
    if (!LogEnabled) return
    log(indent, message)
    println()
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun log(indent: Int = 0, message: Any?) {
    if (!LogEnabled) return
    printIndent(indent)
    print(message)
}

@JvmInline
internal value class LogIndent(val currentLogIndent: Int) {
    @OptIn(ExperimentalContracts::class)
    inline fun <R> logDuration(prefix: String, start: Boolean = true, block: LogIndent.() -> R): R {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        return logDuration(currentLogIndent, prefix, start, block)
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun logLn(message: Any?) = logLn(currentLogIndent, message)
}

@OptIn(ExperimentalContracts::class)
internal inline fun <R> logDuration(
    indent: Int,
    prefix: String,
    start: Boolean = true,
    block: LogIndent.() -> R,
): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    if (!LogEnabled) return LogIndent(0).block()
    if (start) {
        logLn(indent, prefix)
    }
    val (result, duration) = measureTimedValue { LogIndent(indent + 1).block() }

    printIndent(indent)
    print(prefix)
    print(": ")
    println(duration.toString(DurationUnit.MICROSECONDS))
    return result
}

@Suppress("NOTHING_TO_INLINE")
private inline fun printIndent(indent: Int) {
    for (i in 0 until indent) {
        print("  ")
    }
}

internal fun <A> CoroutineScope.asyncImmediate(
    start: CoroutineStart = CoroutineStart.UNDISPATCHED,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> A,
): Deferred<A> = async(start = start, context = Dispatchers.Unconfined + context, block = block)

internal fun CoroutineScope.launchImmediate(
    start: CoroutineStart = CoroutineStart.UNDISPATCHED,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(start = start, context = Dispatchers.Unconfined + context, block = block)

internal suspend fun awaitCancellationAndThen(block: suspend () -> Unit) {
    try {
        awaitCancellation()
    } finally {
        block()
    }
}

internal fun CoroutineScope.invokeOnCancel(
    context: CoroutineContext = EmptyCoroutineContext,
    block: () -> Unit,
): Job =
    launch(context = context, start = CoroutineStart.UNDISPATCHED) {
        awaitCancellationAndThen(block)
    }

internal fun CoroutineScope.childScope(
    context: CoroutineContext = EmptyCoroutineContext
): CoroutineScope {
    val newContext = newCoroutineContext(context)
    val newJob = Job(parent = newContext[Job])
    return CoroutineScope(newContext + newJob)
}

internal fun <A> Iterable<A>.associateByIndex(): Map<Int, A> = buildMap {
    forEachIndexed { index, a -> put(index, a) }
}

internal fun <A, M : MutableMap<Int, A>> Iterable<A>.associateByIndexTo(destination: M): M =
    destination.apply { forEachIndexed { index, a -> put(index, a) } }

internal val Any.hashString: String
    get() = Integer.toHexString(System.identityHashCode(this))
