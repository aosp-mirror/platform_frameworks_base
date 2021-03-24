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

/** A listener that's notified when an ongoing call is started or ended. */
interface OngoingCallListener {
    /** Called when an ongoing call is started. */
    fun onOngoingCallStarted(animate: Boolean)

    /** Called when an ongoing call is ended. */
    fun onOngoingCallEnded(animate: Boolean)
}
