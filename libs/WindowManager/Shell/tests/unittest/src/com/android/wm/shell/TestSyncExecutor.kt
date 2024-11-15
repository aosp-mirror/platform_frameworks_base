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
package com.android.wm.shell

import com.android.wm.shell.common.ShellExecutor

/**
 * Test ShellExecutor that runs everything synchronously.
 */
class TestSyncExecutor : ShellExecutor {
    override fun execute(runnable: Runnable) {
        runnable.run()
    }

    override fun executeDelayed(runnable: Runnable, delayMillis: Long) {
        runnable.run()
    }

    override fun removeCallbacks(runnable: Runnable) {
    }

    override fun hasCallback(runnable: Runnable): Boolean {
        return false
    }
}
