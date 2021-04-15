/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * A container for transport-specific capabilities which is returned by
 * {@link NetworkCapabilities#getTransportInfo()}. Specific networks
 * may provide concrete implementations of this interface.
 * @see android.net.wifi.aware.WifiAwareNetworkInfo
 * @see android.net.wifi.WifiInfo
 */
public interface TransportInfo {

    /**
     * Create a copy of a {@link TransportInfo} with some fields redacted based on the permissions
     * held by the receiving app.
     *
     * <p>
     * Usage by connectivity stack:
     * <ul>
     * <li> Connectivity stack will invoke {@link #getApplicableRedactions()} to find the list
     * of redactions that are required by this {@link TransportInfo} instance.</li>
     * <li> Connectivity stack then loops through each bit in the bitmask returned and checks if the
     * receiving app holds the corresponding permission.
     * <ul>
     * <li> If the app holds the corresponding permission, the bit is cleared from the
     * |redactions| bitmask. </li>
     * <li> If the app does not hold the corresponding permission, the bit is retained in the
     * |redactions| bitmask. </li>
     * </ul>
     * <li> Connectivity stack then invokes {@link #makeCopy(long)} with the necessary |redactions|
     * to create a copy to send to the corresponding app. </li>
     * </ul>
     * </p>
     *
     * @param redactions bitmask of redactions that needs to be performed on this instance.
     * @return Copy of this instance with the necessary redactions.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    default TransportInfo makeCopy(@NetworkCapabilities.RedactionType long redactions) {
        return this;
    }

    /**
     * Returns a bitmask of all the applicable redactions (based on the permissions held by the
     * receiving app) to be performed on this TransportInfo.
     *
     * @return bitmask of redactions applicable on this instance.
     * @see #makeCopy(long)
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    default @NetworkCapabilities.RedactionType long getApplicableRedactions() {
        return NetworkCapabilities.REDACT_NONE;
    }
}
