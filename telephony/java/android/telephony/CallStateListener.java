/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telephony;

import android.annotation.PrivateApi;

/** @hide */
@PrivateApi
public interface CallStateListener {
    /**
     * Notify of a new or updated call.
     * Any time the state of a call is updated, it will alert any listeners. This includes changes
     * of state such as when a call is put on hold or conferenced.
     *
     * @param callId a unique ideCntifier for a given call that can be used to track state changes
     * @param state the new state of the call.
     *              {@see com.android.services.telephony.common.Call$State}
     * @param number the phone number of the call. For some states, this may be blank. However, it
     *               will be populated for any initial state.
     */
    public void onCallStateChanged(int callId, int state, String number);
}
