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

package com.android.systemui.util.kotlin

import android.util.Log

/** Logs message at [Log.DEBUG] level. Won't call the lambda if [DEBUG] is not loggable. */
inline fun logD(tag: String, messageLambda: () -> String) {
    if (Log.isLoggable(tag, Log.DEBUG)) {
        Log.d(tag, messageLambda.invoke())
    }
}

/** Logs message at [Log.VERBOSE] level. Won't call the lambda if [VERBOSE] is not loggable. */
inline fun logV(tag: String, messageLambda: () -> String) {
    if (Log.isLoggable(tag, Log.VERBOSE)) {
        Log.v(tag, messageLambda.invoke())
    }
}

/** Logs message at [Log.INFO] level. Won't call the lambda if [INFO] is not loggable. */
inline fun logI(tag: String, messageLambda: () -> String) {
    if (Log.isLoggable(tag, Log.INFO)) {
        Log.i(tag, messageLambda.invoke())
    }
}
