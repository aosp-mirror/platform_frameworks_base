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

/** A fake [com.android.systemui.util.EventLog] for tests. */
class FakeEventLog : EventLog {
    data class Event(val tag: Int, val value: Any)

    private val _events: MutableList<Event> = mutableListOf()
    val events: List<Event>
        get() = _events

    fun clear() {
        _events.clear()
    }

    override fun writeEvent(tag: Int, value: Int): Int {
        _events.add(Event(tag, value))
        return 1
    }

    override fun writeEvent(tag: Int, value: Long): Int {
        _events.add(Event(tag, value))
        return 1
    }

    override fun writeEvent(tag: Int, value: Float): Int {
        _events.add(Event(tag, value))
        return 1
    }

    override fun writeEvent(tag: Int, value: String): Int {
        _events.add(Event(tag, value))
        return 1
    }

    override fun writeEvent(tag: Int, vararg values: Any): Int {
        _events.add(Event(tag, values))
        return 1
    }
}
