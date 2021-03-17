/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A data class containing all the per-profile network preferences.
 *
 * A given profile can only have one preference.
 */
public class ProfileNetworkPreferences {
    /**
     * A single preference, as it applies to a given user profile.
     */
    public static class Preference {
        @NonNull public final UserHandle user;
        // Capabilities are only null when sending an object to remove the setting for a user
        @Nullable public final NetworkCapabilities capabilities;

        public Preference(@NonNull final UserHandle user,
                @Nullable final NetworkCapabilities capabilities) {
            this.user = user;
            this.capabilities = null == capabilities ? null : new NetworkCapabilities(capabilities);
        }

        /** toString */
        public String toString() {
            return "[ProfileNetworkPreference user=" + user + " caps=" + capabilities + "]";
        }
    }

    @NonNull public final List<Preference> preferences;

    public ProfileNetworkPreferences() {
        preferences = Collections.EMPTY_LIST;
    }

    private ProfileNetworkPreferences(@NonNull final List<Preference> list) {
        preferences = Collections.unmodifiableList(list);
    }

    /**
     * Returns a new object consisting of this object plus the passed preference.
     *
     * If a preference already exists for the same user, it will be replaced by the passed
     * preference. Passing a Preference object containing a null capabilities object is equivalent
     * to (and indeed, implemented as) removing the preference for this user.
     */
    public ProfileNetworkPreferences plus(@NonNull final Preference pref) {
        final ArrayList<Preference> newPrefs = new ArrayList<>();
        for (final Preference existingPref : preferences) {
            if (!existingPref.user.equals(pref.user)) {
                newPrefs.add(existingPref);
            }
        }
        if (null != pref.capabilities) {
            newPrefs.add(pref);
        }
        return new ProfileNetworkPreferences(newPrefs);
    }

    public boolean isEmpty() {
        return preferences.isEmpty();
    }
}
