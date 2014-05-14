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

import com.android.internal.telephony.IThirdPartyCallProvider;

/**
 * Interface provided to ThirdPartyCallService. The service can use this to notify the listener of
 * changes to the call state.
 */
oneway interface IThirdPartyCallListener {
    /**
     * Called by the service when a call provider is available to perform the outgoing or incoming
     * call.
     */
    void onCallProviderAttached(IThirdPartyCallProvider callProvider);

    /**
     * Notifies the listener that ringing has started for this call.
     */
    void onRingingStarted();

    /**
     * Notifies the listener that the call has been successfully established.
     */
    void onCallEstablished();

    /**
     * Notifies the listener that the call has ended.
     */
    void onCallEnded(int reason);
}
