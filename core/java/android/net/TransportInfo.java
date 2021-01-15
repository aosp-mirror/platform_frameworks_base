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
     * Create a copy of a {@link TransportInfo} that will preserve location sensitive fields that
     * were set based on the permissions of the process that originally received it.
     *
     * <p>By default {@link TransportInfo} does not preserve such fields during parceling, as
     * they should not be shared outside of the process that receives them without appropriate
     * checks.
     *
     * @param parcelLocationSensitiveFields Whether the location sensitive fields should be kept
     *                                      when parceling
     * @return Copy of this instance.
     * @hide
     */
    @SystemApi
    @NonNull
    default TransportInfo makeCopy(boolean parcelLocationSensitiveFields) {
        return this;
    }

    /**
     * Returns whether this TransportInfo type has location sensitive fields or not (helps
     * to determine whether to perform a location permission check or not before sending to
     * apps).
     *
     * @return {@code true} if this instance contains location sensitive info, {@code false}
     * otherwise.
     * @hide
     */
    @SystemApi
    default boolean hasLocationSensitiveFields() {
        return false;
    }
}
