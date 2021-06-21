/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.ongoingcall

/** A listener that's notified when the state of an ongoing call has changed. */
interface OngoingCallListener {

    /**
     * Called when the state of an ongoing call has changed in any way that may affect view
     * visibility (including call starting, call stopping, application managing the call becoming
     * visible or invisible).
     */
    fun onOngoingCallStateChanged(animate: Boolean)
}
