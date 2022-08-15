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

package com.android.systemui.shade.testing

import com.android.systemui.shade.NotifPanelEvents

/** Fake implementation of [NotifPanelEvents] for testing. */
class FakeNotifPanelEvents : NotifPanelEvents {

    private val listeners = mutableListOf<NotifPanelEvents.Listener>()

    override fun registerListener(listener: NotifPanelEvents.Listener) {
        listeners.add(listener)
    }

    override fun unregisterListener(listener: NotifPanelEvents.Listener) {
        listeners.remove(listener)
    }

    fun changeExpandImmediate(expandImmediate: Boolean) {
        listeners.forEach { it.onExpandImmediateChanged(expandImmediate) }
    }
}
