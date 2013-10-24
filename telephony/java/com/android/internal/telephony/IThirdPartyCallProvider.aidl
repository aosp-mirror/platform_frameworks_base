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
 * Interface sent to ThirdPartyCallListener.onCallProviderAttached. This is used to control an
 * outgoing or incoming call.
 */
oneway interface IThirdPartyCallProvider {
    /**
     * Mutes or unmutes the call.
     */
    void mute(boolean shouldMute);

    /**
     * Ends the current call. If this is an unanswered incoming call then the call is rejected (for
     * example, a notification is sent to a server that the user declined the call).
     */
    void hangup();

    /**
     * Accepts the incoming call.
     */
    void incomingCallAccept();
}
