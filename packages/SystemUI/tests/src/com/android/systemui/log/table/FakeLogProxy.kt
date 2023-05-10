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

package com.android.systemui.log.table

/**
 * Fake [LogProxy] that collects all lines sent to it. Mimics the ADB logcat format without the
 * timestamp. [FakeLogProxy.d] will write a log like so:
 * ```
 * logger.d("TAG", "message here")
 * // writes this to the [logs] field
 * "D TAG: message here"
 * ```
 *
 * Logs sent to this class are collected as a list of strings for simple test assertions.
 */
class FakeLogProxy : LogProxy {
    val logs: MutableList<String> = mutableListOf()

    override fun v(tag: String, message: String) {
        logs.add("V $tag: $message")
    }

    override fun d(tag: String, message: String) {
        logs.add("D $tag: $message")
    }

    override fun i(tag: String, message: String) {
        logs.add("I $tag: $message")
    }

    override fun w(tag: String, message: String) {
        logs.add("W $tag: $message")
    }

    override fun e(tag: String, message: String) {
        logs.add("E $tag: $message")
    }

    override fun wtf(tag: String, message: String) {
        logs.add("WTF $tag: $message")
    }
}
