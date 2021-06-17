/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.ims.aidl;

import android.telephony.ims.RcsContactUceCapability;

/**
 * Interface used by the framework to respond to OPTIONS requests.
 * {@hide}
 */
oneway interface IOptionsRequestCallback {
    /**
     * Respond to a remote capability request from the contact specified with the capabilities
     * of this device.
     * @param ownCapabilities The capabilities of this device.
     * @param isBlocked True if the user has blocked the number sending this request.
     */
    void respondToCapabilityRequest(in RcsContactUceCapability ownCapabilities, boolean isBlocked);

    /**
     * Respond to a remote capability request from the contact specified with the
     * specified error.
     * @param code The SIP response code to respond with.
     * @param reason A non-null String containing the reason associated with the SIP code.
     */
    void respondToCapabilityRequestWithError(int code, String reason);
}
