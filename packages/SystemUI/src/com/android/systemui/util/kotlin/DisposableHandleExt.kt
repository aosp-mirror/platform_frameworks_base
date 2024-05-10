/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation

/**
 * Suspends to keep getting updates until cancellation. Once cancelled, mark this as eligible for
 * garbage collection.
 *
 * This utility is useful if you want to bind a [repeatWhenAttached] invocation to the lifetime of a
 * coroutine, such that cancelling the coroutine cleans up the handle. For example:
 * ```
 * myFlow.collectLatest { value ->
 *     val disposableHandle = myView.repeatWhenAttached { doStuff() }
 *     doSomethingWith(value)
 *     // un-bind when done
 *     disposableHandle.awaitCancellationThenDispose()
 * }
 * ```
 */
suspend fun DisposableHandle.awaitCancellationThenDispose() {
    try {
        awaitCancellation()
    } finally {
        dispose()
    }
}
