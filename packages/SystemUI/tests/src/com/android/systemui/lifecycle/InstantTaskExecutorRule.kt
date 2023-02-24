/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.lifecycle

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Test rule that makes ArchTaskExecutor main thread assertions pass. There is one such assert
 * in LifecycleRegistry.
 */
class InstantTaskExecutorRule : TestWatcher() {
    // TODO(b/240620122): This is a copy of
    //  androidx/arch/core/executor/testing/InstantTaskExecutorRule which should be replaced
    //  with a dependency on the real library once b/ is cleared.
    override fun starting(description: Description) {
        super.starting(description)
        ArchTaskExecutor.getInstance()
            .setDelegate(
                object : TaskExecutor() {
                    override fun executeOnDiskIO(runnable: Runnable) {
                        runnable.run()
                    }

                    override fun postToMainThread(runnable: Runnable) {
                        runnable.run()
                    }

                    override fun isMainThread(): Boolean {
                        return true
                    }
                }
            )
    }

    override fun finished(description: Description) {
        super.finished(description)
        ArchTaskExecutor.getInstance().setDelegate(null)
    }
}
