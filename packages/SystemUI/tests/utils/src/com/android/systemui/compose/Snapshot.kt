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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Runs the given test [block] in a [TestScope] that's set up such that the Compose snapshot state
 * writes are properly applied to the global snapshot. This is for instance necessary if your test
 * is using `snapshotFlow {}` or any other mechanism that is observing the global snapshot.
 *
 * Note that this isn't needed in a Compose test environment, e.g. if you use the
 * `Compose(Content)TestRule`.
 */
fun TestScope.runTestWithSnapshots(block: suspend TestScope.() -> Unit) {
    val handle = Snapshot.registerGlobalWriteObserver { Snapshot.sendApplyNotifications() }

    try {
        runTest { block() }
    } finally {
        handle.dispose()
    }
}
