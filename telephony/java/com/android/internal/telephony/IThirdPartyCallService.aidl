/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.telephony;

import com.android.internal.telephony.IThirdPartyCallListener;

/**
 * Interface provided by a service to start outgoing calls and attach to incoming calls.
 */
oneway interface IThirdPartyCallService {
    /**
     * Call to start a new outgoing call.
     */
    void outgoingCallInitiate(IThirdPartyCallListener listener, String number);

    /**
     * Call to attach to an incoming call. This is in response to a call to
     * TelephonyManager.newIncomingThirdPartyCall.
     */
    void incomingCallAttach(IThirdPartyCallListener listener, String callId);
}
