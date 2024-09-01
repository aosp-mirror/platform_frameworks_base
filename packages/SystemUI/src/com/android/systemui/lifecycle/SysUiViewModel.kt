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

package com.android.systemui.lifecycle

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.android.app.tracing.coroutines.traceCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Returns a remembered view-model of the type [T]. If the returned instance is also an
 * [Activatable], it's automatically kept active until this composable leaves the composition; if
 * the [key] changes, the old view-model is deactivated and a new one will be instantiated,
 * activated, and returned.
 *
 * The [traceName] is used for coroutine performance tracing purposes. Please try to use a label
 * that's unique enough and easy enough to find in code search; this should help correlate
 * performance findings with actual code. One recommendation: prefer whole string literals instead
 * of some complex concatenation or templating scheme.
 */
@Composable
fun <T> rememberViewModel(
    traceName: String,
    key: Any = Unit,
    factory: () -> T,
): T {
    val instance = remember(key) { factory() }
    if (instance is Activatable) {
        LaunchedEffect(instance) { traceCoroutine(traceName) { instance.activate() } }
    }
    return instance
}

/**
 * Invokes [block] in a new coroutine with a new view-model that is automatically activated whenever
 * `this` [View]'s Window's [WindowLifecycleState] is at least at [minWindowLifecycleState], and is
 * automatically canceled once that is no longer the case.
 *
 * The [traceName] is used for coroutine performance tracing purposes. Please try to use a label
 * that's unique enough and easy enough to find in code search; this should help correlate
 * performance findings with actual code. One recommendation: prefer whole string literals instead
 * of some complex concatenation or templating scheme.
 */
suspend fun <T> View.viewModel(
    traceName: String,
    minWindowLifecycleState: WindowLifecycleState,
    factory: () -> T,
    block: suspend CoroutineScope.(T) -> Unit,
): Nothing =
    repeatOnWindowLifecycle(minWindowLifecycleState) {
        val instance = factory()
        if (instance is Activatable) {
            launch { traceCoroutine(traceName) { instance.activate() } }
        }
        block(instance)
    }
