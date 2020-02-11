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

package android.location;


import android.annotation.NonNull;

/**
 * Location manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class LocationManagerInternal {

    /**
     * Requests that a provider change its allowed state. A provider may or may not honor this
     * request, and if the provider does change its state as a result, that may happen
     * asynchronously after some delay.
     *
     * <p>Setting a provider's state to allowed implies that any consents or terms and conditions
     * that may be necessary to allow the provider are agreed to. Setting a providers state to
     * disallowed implies that any consents or terms and conditions have their agreement revoked.
     *
     * @param provider A location provider as listed by {@link LocationManager#getAllProviders()}
     * @param allowed  Whether the location provider is being requested to allow or disallow
     *                 itself
     * @throws IllegalArgumentException if provider is null
     */
    public abstract void requestSetProviderAllowed(@NonNull String provider, boolean allowed);

    /**
     * Returns true if the given provider is enabled for the given user.
     *
     * @param provider A location provider as listed by {@link LocationManager#getAllProviders()}
     * @param userId   The user id to check
     * @return True if the provider is enabled, false otherwise
     */
    public abstract boolean isProviderEnabledForUser(@NonNull String provider, int userId);

    /**
     * Returns true if the given package belongs to a location provider, and so should be afforded
     * some special privileges.
     *
     * @param packageName The package name to check
     * @return True is the given package belongs to a location provider, false otherwise
     */
    public abstract boolean isProviderPackage(@NonNull String packageName);

    /**
     * Should only be used by GNSS code.
     */
    // TODO: there is no reason for this to exist as part of any API. move all the logic into gnss
    public abstract void sendNiResponse(int notifId, int userResponse);
}
