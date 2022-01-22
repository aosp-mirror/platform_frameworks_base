/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.inflation

import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.util.ListenerSet

/**
 * Helper class that allows distributing bind events regardless of the pipeline.
 *
 * NOTE: This class isn't ideal; this exposes the concept of view inflation as something that can be
 * globally registered for. This is built as it is to provide compatibility with patterns developed
 * for the legacy pipeline. Ideally we'd have functionality that needs to know this information be
 * handled by events that go through the ViewController itself.
 */
open class BindEventManager {
    protected val listeners = ListenerSet<Listener>()

    /** Register a listener */
    fun addListener(listener: Listener) =
        listeners.addIfAbsent(listener)

    /** Deregister a listener */
    fun removeListener(listener: Listener) =
        listeners.remove(listener)

    /** Listener interface for view bind events */
    fun interface Listener {
        fun onViewBound(entry: NotificationEntry)
    }
}
