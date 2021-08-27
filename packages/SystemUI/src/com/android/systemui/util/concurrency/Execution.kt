/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.Looper
import javax.inject.Inject

/**
 * Methods to check or assert that we're on the main thread
 */
interface Execution {
    fun assertIsMainThread()
    fun isMainThread(): Boolean
}

class ExecutionImpl @Inject constructor() : Execution {
    private val mainLooper = Looper.getMainLooper()

    override fun assertIsMainThread() {
        if (!mainLooper.isCurrentThread) {
            throw IllegalStateException("should be called from the main thread." +
                    " Main thread name=" + mainLooper.thread.name +
                    " Thread.currentThread()=" + Thread.currentThread().name)
        }
    }

    override fun isMainThread(): Boolean {
        return mainLooper.isCurrentThread
    }
}

class FakeExecution : Execution {
    var simulateMainThread = true

    override fun assertIsMainThread() {
        if (!simulateMainThread) {
            throw IllegalStateException("should be called from the main thread")
        }
    }

    override fun isMainThread(): Boolean {
        return simulateMainThread
    }
}
