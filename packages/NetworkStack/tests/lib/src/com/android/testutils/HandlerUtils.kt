/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.testutils

import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Executor
import kotlin.test.fail

/**
 * Block until the specified Handler or HandlerThread becomes idle, or until timeoutMs has passed.
 */
fun Handler.waitForIdle(timeoutMs: Long) = waitForIdleHandler(this, timeoutMs)
fun HandlerThread.waitForIdle(timeoutMs: Long) = waitForIdleHandler(this.threadHandler, timeoutMs)
fun waitForIdleHandler(handler: HandlerThread, timeoutMs: Long) {
    waitForIdleHandler(handler.threadHandler, timeoutMs)
}
fun waitForIdleHandler(handler: Handler, timeoutMs: Long) {
    val cv = ConditionVariable(false)
    handler.post(cv::open)
    if (!cv.block(timeoutMs)) {
        fail("Handler did not become idle after ${timeoutMs}ms")
    }
}

/**
 * Block until the given Serial Executor becomes idle, or until timeoutMs has passed.
 */
fun waitForIdleSerialExecutor(executor: Executor, timeoutMs: Long) {
    val cv = ConditionVariable()
    executor.execute(cv::open)
    if (!cv.block(timeoutMs)) {
        fail("Executor did not become idle after ${timeoutMs}ms")
    }
}
