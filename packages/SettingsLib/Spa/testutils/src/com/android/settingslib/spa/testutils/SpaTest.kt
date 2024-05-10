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

package com.android.settingslib.spa.testutils

import java.util.concurrent.TimeoutException

/**
 * Blocks until the given condition is satisfied.
 */
fun waitUntil(timeoutMillis: Long = 1000, condition: () -> Boolean) {
    val startTime = System.currentTimeMillis()
    while (!condition()) {
        // Let Android run measure, draw and in general any other async operations.
        Thread.sleep(10)
        if (System.currentTimeMillis() - startTime > timeoutMillis) {
            throw TimeoutException("Condition still not satisfied after $timeoutMillis ms")
        }
    }
}
