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

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcelable;
import android.util.SparseArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** @hide */
public final class OemNetworkPreferences implements Parcelable {
    /**
     * Use default behavior requesting networks. Equivalent to not setting any preference at all.
     */
    public static final int OEM_NETWORK_PREFERENCE_DEFAULT = 0;

    /**
     * Prefer networks in order: NET_CAPABILITY_NOT_METERED, NET_CAPABILITY_OEM_PAID, default.
     */
    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID = 1;

    /**
     * Prefer networks in order: NET_CAPABILITY_NOT_METERED, NET_CAPABILITY_OEM_PAID.
     */
    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK = 2;

    /**
     * Prefer only NET_CAPABILITY_OEM_PAID networks.
     */
    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY = 3;

    /**
     * Prefer only NET_CAPABILITY_OEM_PRIVATE networks.
     */
    public static final int OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY = 4;

    @NonNull
    private final SparseArray<List<String>> mNetworkMappings;

    @NonNull
    public SparseArray<List<String>> getNetworkPreferences() {
        return mNetworkMappings.clone();
    }

    private OemNetworkPreferences(@NonNull SparseArray<List<String>> networkMappings) {
        Objects.requireNonNull(networkMappings);
        mNetworkMappings = networkMappings.clone();
    }

    @Override
    public String toString() {
        return "OemNetworkPreferences{" + "mNetworkMappings=" + mNetworkMappings + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OemNetworkPreferences that = (OemNetworkPreferences) o;

        return mNetworkMappings.size() == that.mNetworkMappings.size()
                && mNetworkMappings.toString().equals(that.mNetworkMappings.toString());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetworkMappings);
    }

    /**
     * Builder used to create {@link OemNetworkPreferences} objects.  Specify the preferred Network
     * to package name mappings.
     *
     * @hide
     */
    public static final class Builder {
        private final SparseArray<List<String>> mNetworkMappings;

        public Builder() {
            mNetworkMappings = new SparseArray<>();
        }

        /**
         * Add a network preference for a list of packages.
         *
         * @param preference the desired network preference to use
         * @param packages   full package names (e.g.: "com.google.apps.contacts") for apps to use
         *                   the given preference
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder addNetworkPreference(@OemNetworkPreference final int preference,
                @NonNull List<String> packages) {
            Objects.requireNonNull(packages);
            mNetworkMappings.put(preference,
                    Collections.unmodifiableList(new ArrayList<>(packages)));
            return this;
        }

        /**
         * Build {@link OemNetworkPreferences} return the current OEM network preferences.
         */
        @NonNull
        public OemNetworkPreferences build() {
            return new OemNetworkPreferences(mNetworkMappings);
        }
    }

    /** @hide */
    @IntDef(prefix = "OEM_NETWORK_PREFERENCE_", value = {
            OEM_NETWORK_PREFERENCE_DEFAULT,
            OEM_NETWORK_PREFERENCE_OEM_PAID,
            OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK,
            OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY,
            OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OemNetworkPreference {}

    /**
     * Return the string value for OemNetworkPreference
     *
     * @param value int value of OemNetworkPreference
     * @return string version of OemNetworkPreference
     */
    @NonNull
    public static String oemNetworkPreferenceToString(@OemNetworkPreference int value) {
        switch (value) {
            case OEM_NETWORK_PREFERENCE_DEFAULT:
                return "OEM_NETWORK_PREFERENCE_DEFAULT";
            case OEM_NETWORK_PREFERENCE_OEM_PAID:
                return "OEM_NETWORK_PREFERENCE_OEM_PAID";
            case OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK:
                return "OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK";
            case OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY:
                return "OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY";
            case OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY:
                return "OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY";
            default:
                return Integer.toHexString(value);
        }
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeSparseArray(mNetworkMappings);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<OemNetworkPreferences> CREATOR =
            new Parcelable.Creator<OemNetworkPreferences>() {
                @Override
                public OemNetworkPreferences[] newArray(int size) {
                    return new OemNetworkPreferences[size];
                }

                @Override
                public OemNetworkPreferences createFromParcel(@NonNull android.os.Parcel in) {
                    return new OemNetworkPreferences(
                            in.readSparseArray(getClass().getClassLoader()));
                }
            };
}
