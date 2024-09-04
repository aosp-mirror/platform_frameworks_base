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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.android.app.tracing.coroutines.traceCoroutine

/** Defines interface for classes that can be activated to do coroutine work. */
interface Activatable {

    /**
     * Activates this object.
     *
     * Serves as an entrypoint to kick off coroutine work that the object requires in order to keep
     * its state fresh and/or perform side-effects.
     *
     * The method suspends and doesn't return until all work required by the object is finished. In
     * most cases, it's expected for the work to remain ongoing forever so this method will forever
     * suspend its caller until the coroutine that called it is canceled.
     *
     * Implementations could follow this pattern:
     * ```kotlin
     * override suspend fun activate() {
     *     coroutineScope {
     *         launch { ... }
     *         launch { ... }
     *         launch { ... }
     *     }
     * }
     * ```
     *
     * **Must be invoked** by the owner of the object when the object is to become active.
     * Similarly, the work must be canceled by the owner when the objects is to be deactivated.
     *
     * One way to have a parent call this would be by using a `LaunchedEffect` in Compose:
     * ```kotlin
     * @Composable
     * fun MyUi(activatable: Activatable) {
     *     LaunchedEffect(activatable) {
     *         activatable.activate()
     *     }
     * }
     * ```
     */
    suspend fun activate(): Nothing
}

/**
 * Returns a remembered [Activatable] of the type [T] that's automatically kept active until this
 * composable leaves the composition.
 *
 * If the [key] changes, the old [Activatable] is deactivated and a new one will be instantiated,
 * activated, and returned.
 *
 * The [traceName] is used for coroutine performance tracing purposes. Please try to use a label
 * that's unique enough and easy enough to find in code search; this should help correlate
 * performance findings with actual code. One recommendation: prefer whole string literals instead
 * of some complex concatenation or templating scheme.
 */
@Composable
fun <T : Activatable> rememberActivated(
    traceName: String,
    key: Any = Unit,
    factory: () -> T,
): T {
    val instance = remember(key) { factory() }
    LaunchedEffect(instance) { traceCoroutine(traceName) { instance.activate() } }
    return instance
}
