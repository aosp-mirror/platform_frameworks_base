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

package com.android.systemui.util

/**
 * Testable wrapper around {@link android.util.EventLog}.
 *
 * Dagger can inject this wrapper into your classes. The implementation just proxies calls to the
 * real EventLog.
 *
 * In tests, pass an instance of FakeEventLog, which allows you to examine the values passed to the
 * various methods below.
 */
interface EventLog {
    /** @see android.util.EventLog.writeEvent */
    fun writeEvent(tag: Int, value: Int): Int

    /** @see android.util.EventLog.writeEvent */
    fun writeEvent(tag: Int, value: Long): Int

    /** @see android.util.EventLog.writeEvent */
    fun writeEvent(tag: Int, value: Float): Int

    /** @see android.util.EventLog.writeEvent */
    fun writeEvent(tag: Int, value: String): Int

    /** @see android.util.EventLog.writeEvent */
    fun writeEvent(tag: Int, vararg values: Any): Int
}
