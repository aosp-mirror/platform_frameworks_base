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

import com.android.internal.annotations.Immutable;

import java.util.Set;

/**
 * Location manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class LocationManagerInternal {

    /**
     * Listener for changes in provider enabled state.
     */
    public interface ProviderEnabledListener {
        /**
         * Called when the provider enabled state changes for a particular user.
         */
        void onProviderEnabledChanged(String provider, int userId, boolean enabled);
    }

    /**
     * Interface for getting callbacks when a location provider's location tags change.
     *
     * @see LocationTagInfo
     */
    public interface OnProviderLocationTagsChangeListener {

        /**
         * Called when the location tags for a provider change.
         *
         * @param providerLocationTagInfo The tag info for a provider.
         */
        void onLocationTagsChanged(@NonNull LocationTagInfo providerLocationTagInfo);
    }

    /**
     * Returns true if the given provider is enabled for the given user.
     *
     * @param provider A location provider as listed by {@link LocationManager#getAllProviders()}
     * @param userId   The user id to check
     * @return True if the provider is enabled, false otherwise
     */
    public abstract boolean isProviderEnabledForUser(@NonNull String provider, int userId);

    /**
     * Adds a provider enabled listener. The given provider must exist.
     *
     * @param provider The provider to listen for changes
     * @param listener The listener
     */
    public abstract void addProviderEnabledListener(String provider,
            ProviderEnabledListener listener);

    /**
     * Removes a provider enabled listener. The given provider must exist.
     *
     * @param provider The provider to listen for changes
     * @param listener The listener
     */
    public abstract void removeProviderEnabledListener(String provider,
            ProviderEnabledListener listener);

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

    /**
     * Returns the GNSS provided time.
     *
     * @return LocationTime object that includes the current time, according to the GNSS location
     * provider, and the elapsed nanos since boot the current time was computed at.
     */
    public abstract @Nullable LocationTime getGnssTimeMillis();

    /**
     * Sets a listener for changes in the location providers' tags. Passing
     * {@code null} clears the current listener.
     *
     * @param listener The listener.
     */
    public abstract void setOnProviderLocationTagsChangeListener(
            @Nullable OnProviderLocationTagsChangeListener listener);

    /**
     * This class represents the location permission tags used by the location provider
     * packages in a given UID. These tags are strictly used for accessing state guarded
     * by the location permission(s) by a location provider which are required for the
     * provider to fulfill its function as being a location provider.
     */
    @Immutable
    public static class LocationTagInfo {
        private final int mUid;

        @NonNull
        private final String mPackageName;

        @Nullable
        private final Set<String> mLocationTags;

        public LocationTagInfo(int uid, @NonNull String packageName,
                @Nullable Set<String> locationTags) {
            mUid = uid;
            mPackageName = packageName;
            mLocationTags = locationTags;
        }

        /**
         * @return The UID for which tags are related.
         */
        public int getUid() {
            return mUid;
        }

        /**
         * @return The package for which tags are related.
         */
        @NonNull
        public String getPackageName() {
            return mPackageName;
        }

        /**
         * @return The tags for the package used for location related accesses.
         */
        @Nullable
        public Set<String> getTags() {
            return mLocationTags;
        }
    }
}
