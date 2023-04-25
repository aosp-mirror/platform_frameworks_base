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

import android.util.Log
import javax.inject.Inject

/** Dagger-friendly interface so we can inject a fake [android.util.Log] in tests */
interface LogProxy {
    /** verbose log */
    fun v(tag: String, message: String)

    /** debug log */
    fun d(tag: String, message: String)

    /** info log */
    fun i(tag: String, message: String)

    /** warning log */
    fun w(tag: String, message: String)

    /** error log */
    fun e(tag: String, message: String)

    /** wtf log */
    fun wtf(tag: String, message: String)
}

class LogProxyDefault @Inject constructor() : LogProxy {
    override fun v(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun wtf(tag: String, message: String) {
        Log.wtf(tag, message)
    }
}
