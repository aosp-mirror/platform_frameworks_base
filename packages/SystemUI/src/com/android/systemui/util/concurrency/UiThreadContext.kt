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

package com.android.systemui.util.concurrency

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.android.systemui.util.Assert
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

private const val DEFAULT_TIMEOUT = 150L

class UiThreadContext(
    val looper: Looper,
    val handler: Handler,
    val executor: Executor,
    val choreographer: Choreographer
) {
    fun isCurrentThread() {
        Assert.isCurrentThread(looper)
    }

    fun <T> runWithScissors(block: () -> T): T {
        return handler.runWithScissors(block)
    }

    fun runWithScissors(block: Runnable) {
        handler.runWithScissors(block, DEFAULT_TIMEOUT)
    }
}

fun <T> Handler.runWithScissors(block: () -> T): T {
    val returnedValue = AtomicReference<T>()
    runWithScissors({ returnedValue.set(block()) }, DEFAULT_TIMEOUT)
    return returnedValue.get()!!
}
