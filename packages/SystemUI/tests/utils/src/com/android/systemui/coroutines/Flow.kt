/*
 * Copyright (C) 2022 The Android Open Source Project
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

@file:Suppress("OPT_IN_USAGE")

package com.android.systemui.coroutines

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * Collect [flow] in a new [Job] and return a getter for the last collected value.
 *
 * ```
 * fun myTest() = runTest {
 *   // ...
 *   val actual by collectLastValue(underTest.flow)
 *   assertThat(actual).isEqualTo(expected)
 * }
 * ```
 */
fun <T> TestScope.collectLastValue(
    flow: Flow<T>,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
): FlowValue<T?> {
    val values by
        collectValues(
            flow = flow,
            context = context,
            start = start,
        )
    return FlowValueImpl { values.lastOrNull() }
}

/**
 * Collect [flow] in a new [Job] and return a getter for the collection of values collected.
 *
 * ```
 * fun myTest() = runTest {
 *   // ...
 *   val values by collectValues(underTest.flow)
 *   assertThat(values).isEqualTo(listOf(expected1, expected2, ...))
 * }
 * ```
 */
fun <T> TestScope.collectValues(
    flow: Flow<T>,
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
): FlowValue<List<T>> {
    val values = mutableListOf<T>()
    backgroundScope.launch(context, start) { flow.collect(values::add) }
    return FlowValueImpl {
        runCurrent()
        values.toList()
    }
}

/** @see collectLastValue */
interface FlowValue<T> : ReadOnlyProperty<Any?, T> {
    operator fun invoke(): T
}

private class FlowValueImpl<T>(private val block: () -> T) : FlowValue<T> {
    override operator fun invoke(): T = block()
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = invoke()
}
