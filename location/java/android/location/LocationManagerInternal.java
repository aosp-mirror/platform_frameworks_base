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
import android.annotation.Nullable;
import android.location.util.identity.CallerIdentity;

/**
 * Location manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class LocationManagerInternal {

    /**
     * Returns true if the given provider is enabled for the given user.
     *
     * @param provider A location provider as listed by {@link LocationManager#getAllProviders()}
     * @param userId   The user id to check
     * @return True if the provider is enabled, false otherwise
     */
    public abstract boolean isProviderEnabledForUser(@NonNull String provider, int userId);

    /**
     * Returns true if the given identity is a location provider.
     *
     * @param provider The provider to check, or null to check every provider
     * @param identity The identity to match
     * @return True if the given identity matches either the given location provider or any
     * provider, and false otherwise
     */
    public abstract boolean isProvider(@Nullable String provider, @NonNull CallerIdentity identity);

    /**
     * Should only be used by GNSS code.
     */
    // TODO: there is no reason for this to exist as part of any API. move all the logic into gnss
    public abstract void sendNiResponse(int notifId, int userResponse);
}
