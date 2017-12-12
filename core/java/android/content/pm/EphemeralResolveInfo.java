/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.IntentFilter;
import android.content.pm.InstantAppResolveInfo.InstantAppDigest;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Information about an ephemeral application.
 * @hide
 * @removed
 */
@Deprecated
@SystemApi
public final class EphemeralResolveInfo implements Parcelable {
    /** Algorithm that will be used to generate the domain digest */
    public static final String SHA_ALGORITHM = "SHA-256";

    private final InstantAppResolveInfo mInstantAppResolveInfo;
    @Deprecated
    private final List<IntentFilter> mLegacyFilters;

    @Deprecated
    public EphemeralResolveInfo(@NonNull Uri uri, @NonNull String packageName,
            @NonNull List<IntentFilter> filters) {
        if (uri == null || packageName == null || filters == null || filters.isEmpty()) {
            throw new IllegalArgumentException();
        }
        final List<EphemeralIntentFilter> ephemeralFilters = new ArrayList<>(1);
        ephemeralFilters.add(new EphemeralIntentFilter(packageName, filters));
        mInstantAppResolveInfo = new InstantAppResolveInfo(uri.getHost(), packageName,
                createInstantAppIntentFilterList(ephemeralFilters));
        mLegacyFilters = new ArrayList<IntentFilter>(filters.size());
        mLegacyFilters.addAll(filters);
    }

    @Deprecated
    public EphemeralResolveInfo(@NonNull EphemeralDigest digest, @Nullable String packageName,
            @Nullable List<EphemeralIntentFilter> filters) {
        this(digest, packageName, filters, -1 /*versionCode*/);
    }

    public EphemeralResolveInfo(@NonNull EphemeralDigest digest, @Nullable String packageName,
            @Nullable List<EphemeralIntentFilter> filters, int versionCode) {
        mInstantAppResolveInfo = new InstantAppResolveInfo(
                digest.getInstantAppDigest(), packageName,
                createInstantAppIntentFilterList(filters), versionCode);
        mLegacyFilters = null;
    }

    public EphemeralResolveInfo(@NonNull String hostName, @Nullable String packageName,
            @Nullable List<EphemeralIntentFilter> filters) {
        this(new EphemeralDigest(hostName), packageName, filters);
    }

    EphemeralResolveInfo(Parcel in) {
        mInstantAppResolveInfo = in.readParcelable(null /*loader*/);
        mLegacyFilters = new ArrayList<IntentFilter>();
        in.readList(mLegacyFilters, null /*loader*/);
    }

    /** @hide */
    public InstantAppResolveInfo getInstantAppResolveInfo() {
        return mInstantAppResolveInfo;
    }

    private static List<InstantAppIntentFilter> createInstantAppIntentFilterList(
            List<EphemeralIntentFilter> filters) {
        if (filters == null) {
            return null;
        }
        final int filterCount = filters.size();
        final List<InstantAppIntentFilter> returnList = new ArrayList<>(filterCount);
        for (int i = 0; i < filterCount; i++) {
            returnList.add(filters.get(i).getInstantAppIntentFilter());
        }
        return returnList;
    }

    public byte[] getDigestBytes() {
        return mInstantAppResolveInfo.getDigestBytes();
    }

    public int getDigestPrefix() {
        return mInstantAppResolveInfo.getDigestPrefix();
    }

    public String getPackageName() {
        return mInstantAppResolveInfo.getPackageName();
    }

    public List<EphemeralIntentFilter> getIntentFilters() {
        final List<InstantAppIntentFilter> filters = mInstantAppResolveInfo.getIntentFilters();
        final int filterCount = filters.size();
        final List<EphemeralIntentFilter> returnList = new ArrayList<>(filterCount);
        for (int i = 0; i < filterCount; i++) {
            returnList.add(new EphemeralIntentFilter(filters.get(i)));
        }
        return returnList;
    }

    public int getVersionCode() {
        return mInstantAppResolveInfo.getVersionCode();
    }

    @Deprecated
    public List<IntentFilter> getFilters() {
        return mLegacyFilters;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mInstantAppResolveInfo, flags);
        out.writeList(mLegacyFilters);
    }

    public static final Parcelable.Creator<EphemeralResolveInfo> CREATOR
            = new Parcelable.Creator<EphemeralResolveInfo>() {
        @Override
        public EphemeralResolveInfo createFromParcel(Parcel in) {
            return new EphemeralResolveInfo(in);
        }
        @Override
        public EphemeralResolveInfo[] newArray(int size) {
            return new EphemeralResolveInfo[size];
        }
    };

    /**
     * Helper class to generate and store each of the digests and prefixes
     * sent to the Ephemeral Resolver.
     * <p>
     * Since intent filters may want to handle multiple hosts within a
     * domain [eg “*.google.com”], the resolver is presented with multiple
     * hash prefixes. For example, "a.b.c.d.e" generates digests for
     * "d.e", "c.d.e", "b.c.d.e" and "a.b.c.d.e".
     *
     * @hide
     */
    @SystemApi
    public static final class EphemeralDigest implements Parcelable {
        private final InstantAppDigest mInstantAppDigest;

        public EphemeralDigest(@NonNull String hostName) {
            this(hostName, -1 /*maxDigests*/);
        }

        /** @hide */
        public EphemeralDigest(@NonNull String hostName, int maxDigests) {
            mInstantAppDigest = new InstantAppDigest(hostName, maxDigests);
        }

        EphemeralDigest(Parcel in) {
            mInstantAppDigest = in.readParcelable(null /*loader*/);
        }

        /** @hide */
        InstantAppDigest getInstantAppDigest() {
            return mInstantAppDigest;
        }

        public byte[][] getDigestBytes() {
            return mInstantAppDigest.getDigestBytes();
        }

        public int[] getDigestPrefix() {
            return mInstantAppDigest.getDigestPrefix();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeParcelable(mInstantAppDigest, flags);
        }

        @SuppressWarnings("hiding")
        public static final Parcelable.Creator<EphemeralDigest> CREATOR =
                new Parcelable.Creator<EphemeralDigest>() {
            @Override
            public EphemeralDigest createFromParcel(Parcel in) {
                return new EphemeralDigest(in);
            }
            @Override
            public EphemeralDigest[] newArray(int size) {
                return new EphemeralDigest[size];
            }
        };
    }
}
