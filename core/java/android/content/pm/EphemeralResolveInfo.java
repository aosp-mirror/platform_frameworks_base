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
 */
@SystemApi
public final class EphemeralResolveInfo implements Parcelable {
    /** Algorithm that will be used to generate the domain digest */
    public static final String SHA_ALGORITHM = "SHA-256";

    private final EphemeralDigest mDigest;
    private final String mPackageName;
    /** The filters used to match domain */
    private final List<EphemeralIntentFilter> mFilters;
    /** The version code of the app that this class resolves to */
    private final int mVersionCode;
    /** Filters only for legacy clients */
    @Deprecated
    private final List<IntentFilter> mLegacyFilters;

    @Deprecated
    public EphemeralResolveInfo(@NonNull Uri uri, @NonNull String packageName,
            @NonNull List<IntentFilter> filters) {
        if (uri == null || packageName == null || filters == null || filters.isEmpty()) {
            throw new IllegalArgumentException();
        }
        mDigest = new EphemeralDigest(uri.getHost());
        mPackageName = packageName;
        mFilters = new ArrayList<EphemeralIntentFilter>();
        mFilters.add(new EphemeralIntentFilter(packageName, filters));
        mLegacyFilters = new ArrayList<IntentFilter>(filters.size());
        mLegacyFilters.addAll(filters);
        mVersionCode = -1;
    }

    @Deprecated
    public EphemeralResolveInfo(@NonNull EphemeralDigest digest, @Nullable String packageName,
            @Nullable List<EphemeralIntentFilter> filters) {
        this(digest, packageName, filters, -1 /*versionCode*/);
    }

    public EphemeralResolveInfo(@NonNull EphemeralDigest digest, @Nullable String packageName,
            @Nullable List<EphemeralIntentFilter> filters, int versionConde) {
        // validate arguments
        if ((packageName == null && (filters != null && filters.size() != 0))
                || (packageName != null && (filters == null || filters.size() == 0))) {
            throw new IllegalArgumentException();
        }
        mDigest = digest;
        if (filters != null) {
            mFilters = new ArrayList<EphemeralIntentFilter>(filters.size());
            mFilters.addAll(filters);
        } else {
            mFilters = null;
        }
        mLegacyFilters = null;
        mPackageName = packageName;
        mVersionCode = versionConde;
    }

    public EphemeralResolveInfo(@NonNull String hostName, @Nullable String packageName,
            @Nullable List<EphemeralIntentFilter> filters) {
        this(new EphemeralDigest(hostName), packageName, filters);
    }

    EphemeralResolveInfo(Parcel in) {
        mDigest = in.readParcelable(null /*loader*/);
        mPackageName = in.readString();
        mFilters = new ArrayList<EphemeralIntentFilter>();
        in.readList(mFilters, null /*loader*/);
        mVersionCode = in.readInt();
        mLegacyFilters = new ArrayList<IntentFilter>();
        in.readList(mLegacyFilters, null /*loader*/);
    }

    public byte[] getDigestBytes() {
        return mDigest.getDigestBytes()[0];
    }

    public int getDigestPrefix() {
        return mDigest.getDigestPrefix()[0];
    }

    public String getPackageName() {
        return mPackageName;
    }

    public List<EphemeralIntentFilter> getIntentFilters() {
        return mFilters;
    }

    public int getVersionCode() {
        return mVersionCode;
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
        out.writeParcelable(mDigest, flags);
        out.writeString(mPackageName);
        out.writeList(mFilters);
        out.writeInt(mVersionCode);
        out.writeList(mLegacyFilters);
    }

    public static final Parcelable.Creator<EphemeralResolveInfo> CREATOR
            = new Parcelable.Creator<EphemeralResolveInfo>() {
        public EphemeralResolveInfo createFromParcel(Parcel in) {
            return new EphemeralResolveInfo(in);
        }

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
        private static final int DIGEST_MASK = 0xfffff000;
        private static final int DIGEST_PREFIX_COUNT = 5;
        /** Full digest of the domain hashes */
        private final byte[][] mDigestBytes;
        /** The first 4 bytes of the domain hashes */
        private final int[] mDigestPrefix;

        public EphemeralDigest(@NonNull String hostName) {
            this(hostName, -1 /*maxDigests*/);
        }

        /** @hide */
        public EphemeralDigest(@NonNull String hostName, int maxDigests) {
            if (hostName == null) {
                throw new IllegalArgumentException();
            }
            mDigestBytes = generateDigest(hostName.toLowerCase(Locale.ENGLISH), maxDigests);
            mDigestPrefix = new int[mDigestBytes.length];
            for (int i = 0; i < mDigestBytes.length; i++) {
                mDigestPrefix[i] =
                        ((mDigestBytes[i][0] & 0xFF) << 24
                                | (mDigestBytes[i][1] & 0xFF) << 16
                                | (mDigestBytes[i][2] & 0xFF) << 8
                                | (mDigestBytes[i][3] & 0xFF) << 0)
                        & DIGEST_MASK;
            }
        }

        private static byte[][] generateDigest(String hostName, int maxDigests) {
            ArrayList<byte[]> digests = new ArrayList<>();
            try {
                final MessageDigest digest = MessageDigest.getInstance(SHA_ALGORITHM);
                if (maxDigests <= 0) {
                    final byte[] hostBytes = hostName.getBytes();
                    digests.add(digest.digest(hostBytes));
                } else {
                    int prevDot = hostName.lastIndexOf('.');
                    prevDot = hostName.lastIndexOf('.', prevDot - 1);
                    // shortcut for short URLs
                    if (prevDot < 0) {
                        digests.add(digest.digest(hostName.getBytes()));
                    } else {
                        byte[] hostBytes =
                                hostName.substring(prevDot + 1, hostName.length()).getBytes();
                        digests.add(digest.digest(hostBytes));
                        int digestCount = 1;
                        while (prevDot >= 0 && digestCount < maxDigests) {
                            prevDot = hostName.lastIndexOf('.', prevDot - 1);
                            hostBytes =
                                    hostName.substring(prevDot + 1, hostName.length()).getBytes();
                            digests.add(digest.digest(hostBytes));
                            digestCount++;
                        }
                    }
                }
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("could not find digest algorithm");
            }
            return digests.toArray(new byte[digests.size()][]);
        }

        EphemeralDigest(Parcel in) {
            final int digestCount = in.readInt();
            if (digestCount == -1) {
                mDigestBytes = null;
            } else {
                mDigestBytes = new byte[digestCount][];
                for (int i = 0; i < digestCount; i++) {
                    mDigestBytes[i] = in.createByteArray();
                }
            }
            mDigestPrefix = in.createIntArray();
        }

        public byte[][] getDigestBytes() {
            return mDigestBytes;
        }

        public int[] getDigestPrefix() {
            return mDigestPrefix;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            if (mDigestBytes == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(mDigestBytes.length);
                for (int i = 0; i < mDigestBytes.length; i++) {
                    out.writeByteArray(mDigestBytes[i]);
                }
            }
            out.writeIntArray(mDigestPrefix);
        }

        @SuppressWarnings("hiding")
        public static final Parcelable.Creator<EphemeralDigest> CREATOR =
                new Parcelable.Creator<EphemeralDigest>() {
            public EphemeralDigest createFromParcel(Parcel in) {
                return new EphemeralDigest(in);
            }

            public EphemeralDigest[] newArray(int size) {
                return new EphemeralDigest[size];
            }
        };
    }
}
