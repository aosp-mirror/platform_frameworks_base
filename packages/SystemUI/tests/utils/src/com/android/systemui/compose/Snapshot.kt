/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.compose

import androidx.compose.runtime.snapshots.Snapshot
import com.android.systemui.kosmos.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Runs the given test [block] in a [TestScope] that's set up such that the Compose snapshot state
 * is settled eagerly. This is the Compose equivalent to using an [UnconfinedTestDispatcher] or
 * using [runCurrent] a lot.
 *
 * Note that this shouldn't be needed or used in a Compose test environment.
 */
fun TestScope.runTestWithSnapshots(block: suspend TestScope.() -> Unit) {
    val handle = Snapshot.registerGlobalWriteObserver { Snapshot.sendApplyNotifications() }

    try {
        runTest { block() }
    } finally {
        handle.dispose()
    }
}
