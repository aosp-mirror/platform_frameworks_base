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

package com.android.settingslib.datastore

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * Adapter of [Handler] and [Executor], where the task is executed on handler with given looper.
 *
 * When current looper is same with the given looper, task passed to [Executor.execute] will be
 * executed immediately to achieve better performance.
 *
 * @param looper Looper of the handler.
 */
open class HandlerExecutor(looper: Looper) : Handler(looper), Executor {

    override fun execute(command: Runnable) {
        if (looper == Looper.myLooper()) {
            command.run()
        } else {
            post(command)
        }
    }

    companion object {
        /** The main thread [HandlerExecutor]. */
        val main = HandlerExecutor(Looper.getMainLooper())
    }
}
