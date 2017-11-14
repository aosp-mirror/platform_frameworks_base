/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * Information about an instant application.
 * @hide
 */
@SystemApi
public final class InstantAppResolveInfo implements Parcelable {
    /** Algorithm that will be used to generate the domain digest */
    private static final String SHA_ALGORITHM = "SHA-256";

    private final InstantAppDigest mDigest;
    private final String mPackageName;
    /** The filters used to match domain */
    private final List<InstantAppIntentFilter> mFilters;
    /** The version code of the app that this class resolves to */
    private final int mVersionCode;

    public InstantAppResolveInfo(@NonNull InstantAppDigest digest, @Nullable String packageName,
            @Nullable List<InstantAppIntentFilter> filters, int versionCode) {
        // validate arguments
        if ((packageName == null && (filters != null && filters.size() != 0))
                || (packageName != null && (filters == null || filters.size() == 0))) {
            throw new IllegalArgumentException();
        }
        mDigest = digest;
        if (filters != null) {
            mFilters = new ArrayList<InstantAppIntentFilter>(filters.size());
            mFilters.addAll(filters);
        } else {
            mFilters = null;
        }
        mPackageName = packageName;
        mVersionCode = versionCode;
    }

    public InstantAppResolveInfo(@NonNull String hostName, @Nullable String packageName,
            @Nullable List<InstantAppIntentFilter> filters) {
        this(new InstantAppDigest(hostName), packageName, filters, -1 /*versionCode*/);
    }

    InstantAppResolveInfo(Parcel in) {
        mDigest = in.readParcelable(null /*loader*/);
        mPackageName = in.readString();
        mFilters = new ArrayList<InstantAppIntentFilter>();
        in.readList(mFilters, null /*loader*/);
        mVersionCode = in.readInt();
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

    public List<InstantAppIntentFilter> getIntentFilters() {
        return mFilters;
    }

    public int getVersionCode() {
        return mVersionCode;
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
    }

    public static final Parcelable.Creator<InstantAppResolveInfo> CREATOR
            = new Parcelable.Creator<InstantAppResolveInfo>() {
        public InstantAppResolveInfo createFromParcel(Parcel in) {
            return new InstantAppResolveInfo(in);
        }

        public InstantAppResolveInfo[] newArray(int size) {
            return new InstantAppResolveInfo[size];
        }
    };

    /**
     * Helper class to generate and store each of the digests and prefixes
     * sent to the Instant App Resolver.
     * <p>
     * Since intent filters may want to handle multiple hosts within a
     * domain [eg “*.google.com”], the resolver is presented with multiple
     * hash prefixes. For example, "a.b.c.d.e" generates digests for
     * "d.e", "c.d.e", "b.c.d.e" and "a.b.c.d.e".
     *
     * @hide
     */
    @SystemApi
    public static final class InstantAppDigest implements Parcelable {
        private static final int DIGEST_MASK = 0xfffff000;
        private static final int DIGEST_PREFIX_COUNT = 5;
        /** Full digest of the domain hashes */
        private final byte[][] mDigestBytes;
        /** The first 4 bytes of the domain hashes */
        private final int[] mDigestPrefix;

        public InstantAppDigest(@NonNull String hostName) {
            this(hostName, -1 /*maxDigests*/);
        }

        /** @hide */
        public InstantAppDigest(@NonNull String hostName, int maxDigests) {
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

        InstantAppDigest(Parcel in) {
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
        public static final Parcelable.Creator<InstantAppDigest> CREATOR =
                new Parcelable.Creator<InstantAppDigest>() {
            @Override
            public InstantAppDigest createFromParcel(Parcel in) {
                return new InstantAppDigest(in);
            }
            @Override
            public InstantAppDigest[] newArray(int size) {
                return new InstantAppDigest[size];
            }
        };
    }
}
