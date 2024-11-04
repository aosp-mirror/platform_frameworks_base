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

package com.android.systemui.util.kotlin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import com.android.app.tracing.coroutines.launchTraced as launch

/**
 * Runs the given [blocks] in parallel, returning the result of the first one to complete, and
 * cancelling all others.
 */
suspend fun <R> race(vararg blocks: suspend () -> R): R = coroutineScope {
    val completion = CompletableDeferred<R>()
    val raceJob = launch {
        for (block in blocks) {
            launch { completion.complete(block()) }
        }
    }
    completion.await().also { raceJob.cancel() }
}
