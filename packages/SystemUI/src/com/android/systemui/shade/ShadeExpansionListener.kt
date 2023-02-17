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
package com.android.systemui.shade

/** A listener interface to be notified of expansion events for the notification panel. */
fun interface ShadeExpansionListener {
    /**
     * Invoked whenever the notification panel expansion changes, at every animation frame. This is
     * the main expansion that happens when the user is swiping up to dismiss the lock screen and
     * swiping to pull down the notification shade.
     */
    fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent)
}
