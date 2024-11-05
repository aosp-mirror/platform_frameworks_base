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

import com.android.systemui.kairos.internal.util.asyncImmediate
import com.android.systemui.kairos.internal.util.launchImmediate
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

internal typealias DeferScope = CoroutineScope

internal inline fun DeferScope.deferAction(
    start: CoroutineStart = CoroutineStart.UNDISPATCHED,
    crossinline block: suspend () -> Unit,
): Job {
    check(isActive) { "Cannot perform deferral, scope already closed." }
    return launchImmediate(start, CoroutineName("deferAction")) { block() }
}

internal inline fun <R> DeferScope.deferAsync(
    start: CoroutineStart = CoroutineStart.UNDISPATCHED,
    crossinline block: suspend () -> R,
): Deferred<R> {
    check(isActive) { "Cannot perform deferral, scope already closed." }
    return asyncImmediate(start, CoroutineName("deferAsync")) { block() }
}

internal suspend inline fun <A> deferScope(noinline block: suspend DeferScope.() -> A): A =
    coroutineScope(block)
