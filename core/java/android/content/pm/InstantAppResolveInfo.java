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
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Describes an externally resolvable instant application. There are three states that this class
 * can represent: <p/>
 * <ul>
 *     <li>
 *         The first, usable only for non http/s intents, implies that the resolver cannot
 *         immediately resolve this intent and would prefer that resolution be deferred to the
 *         instant app installer. Represent this state with {@link #InstantAppResolveInfo(Bundle)}.
 *         If the {@link android.content.Intent} has the scheme set to http/s and a set of digest
 *         prefixes were passed into one of the resolve methods in
 *         {@link android.app.InstantAppResolverService}, this state cannot be used.
 *     </li>
 *     <li>
 *         The second represents a partial match and is constructed with any of the other
 *         constructors. By setting one or more of the {@link Nullable}arguments to null, you
 *         communicate to the resolver in response to
 *         {@link android.app.InstantAppResolverService#onGetInstantAppResolveInfo(Intent, int[],
 *                String, InstantAppResolverService.InstantAppResolutionCallback)}
 *         that you need a 2nd round of resolution to complete the request.
 *     </li>
 *     <li>
 *         The third represents a complete match and is constructed with all @Nullable parameters
 *         populated.
 *     </li>
 * </ul>
 * @hide
 */
@SystemApi
public final class InstantAppResolveInfo implements Parcelable {
    /** Algorithm that will be used to generate the domain digest */
    private static final String SHA_ALGORITHM = "SHA-256";

    private static final byte[] EMPTY_DIGEST = new byte[0];

    private final InstantAppDigest mDigest;
    private final String mPackageName;
    /** The filters used to match domain */
    private final List<InstantAppIntentFilter> mFilters;
    /** The version code of the app that this class resolves to */
    private final long mVersionCode;
    /** Data about the app that should be passed along to the Instant App installer on resolve */
    private final Bundle mExtras;
    /**
     * A flag that indicates that the resolver is aware that an app may match, but would prefer
     * that the installer get the sanitized intent to decide.
     */
    private final boolean mShouldLetInstallerDecide;

    /** Constructor for intent-based InstantApp resolution results. */
    public InstantAppResolveInfo(@NonNull InstantAppDigest digest, @Nullable String packageName,
            @Nullable List<InstantAppIntentFilter> filters, int versionCode) {
        this(digest, packageName, filters, (long) versionCode, null /* extras */);
    }

    /** Constructor for intent-based InstantApp resolution results with extras. */
    public InstantAppResolveInfo(@NonNull InstantAppDigest digest, @Nullable String packageName,
            @Nullable List<InstantAppIntentFilter> filters, long versionCode,
            @Nullable Bundle extras) {
        this(digest, packageName, filters, versionCode, extras, false);
    }

    /** Constructor for intent-based InstantApp resolution results by hostname. */
    public InstantAppResolveInfo(@NonNull String hostName, @Nullable String packageName,
            @Nullable List<InstantAppIntentFilter> filters) {
        this(new InstantAppDigest(hostName), packageName, filters, -1 /*versionCode*/,
                null /* extras */);
    }

    /**
     * Constructor that indicates that resolution could be delegated to the installer when the
     * sanitized intent contains enough information to resolve completely.
     */
    public InstantAppResolveInfo(@Nullable Bundle extras) {
        this(InstantAppDigest.UNDEFINED, null, null, -1, extras, true);
    }

    private InstantAppResolveInfo(@NonNull InstantAppDigest digest, @Nullable String packageName,
            @Nullable List<InstantAppIntentFilter> filters, long versionCode,
            @Nullable Bundle extras, boolean shouldLetInstallerDecide) {
        // validate arguments
        if ((packageName == null && (filters != null && filters.size() != 0))
                || (packageName != null && (filters == null || filters.size() == 0))) {
            throw new IllegalArgumentException();
        }
        mDigest = digest;
        if (filters != null) {
            mFilters = new ArrayList<>(filters.size());
            mFilters.addAll(filters);
        } else {
            mFilters = null;
        }
        mPackageName = packageName;
        mVersionCode = versionCode;
        mExtras = extras;
        mShouldLetInstallerDecide = shouldLetInstallerDecide;
    }

    InstantAppResolveInfo(Parcel in) {
        mShouldLetInstallerDecide = in.readBoolean();
        mExtras = in.readBundle();
        if (mShouldLetInstallerDecide) {
            mDigest = InstantAppDigest.UNDEFINED;
            mPackageName = null;
            mFilters = Collections.emptyList();
            mVersionCode = -1;
        } else {
            mDigest = in.readParcelable(null /*loader*/);
            mPackageName = in.readString();
            mFilters = new ArrayList<>();
            in.readList(mFilters, null /*loader*/);
            mVersionCode = in.readLong();
        }
    }

    /**
     * Returns true if the resolver is aware that an app may match, but would prefer
     * that the installer get the sanitized intent to decide. This should not be true for
     * resolutions that include a host and will be ignored in such cases.
     */
    public boolean shouldLetInstallerDecide() {
        return mShouldLetInstallerDecide;
    }

    public byte[] getDigestBytes() {
        return mDigest.mDigestBytes.length > 0 ? mDigest.getDigestBytes()[0] : EMPTY_DIGEST;
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

    /**
     * @deprecated Use {@link #getLongVersionCode} instead.
     */
    @Deprecated
    public int getVersionCode() {
        return (int) (mVersionCode & 0xffffffff);
    }

    public long getLongVersionCode() {
        return mVersionCode;
    }

    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeBoolean(mShouldLetInstallerDecide);
        out.writeBundle(mExtras);
        if (mShouldLetInstallerDecide) {
            return;
        }
        out.writeParcelable(mDigest, flags);
        out.writeString(mPackageName);
        out.writeList(mFilters);
        out.writeLong(mVersionCode);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<InstantAppResolveInfo> CREATOR
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
        static final int DIGEST_MASK = 0xfffff000;

        /**
         * A special instance that represents and undefined digest used for cases that a host was
         * not provided or is irrelevant to the response.
         */
        public static final InstantAppDigest UNDEFINED =
                new InstantAppDigest(new byte[][]{}, new int[]{});

        private static Random sRandom = null;
        static {
            try {
                sRandom = SecureRandom.getInstance("SHA1PRNG");
            } catch (NoSuchAlgorithmException e) {
                // oh well
                sRandom = new Random();
            }
        }
        /** Full digest of the domain hashes */
        private final byte[][] mDigestBytes;
        /** The first 5 bytes of the domain hashes */
        private final int[] mDigestPrefix;
        /** The first 5 bytes of the domain hashes interspersed with random data */
        private int[] mDigestPrefixSecure;

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

        private InstantAppDigest(byte[][] digestBytes, int[] prefix) {
            this.mDigestPrefix = prefix;
            this.mDigestBytes = digestBytes;
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
            mDigestPrefixSecure = in.createIntArray();
        }

        public byte[][] getDigestBytes() {
            return mDigestBytes;
        }

        public int[] getDigestPrefix() {
            return mDigestPrefix;
        }

        /**
         * Returns a digest prefix with additional random prefixes interspersed.
         * @hide
         */
        public int[] getDigestPrefixSecure() {
            if (this == InstantAppResolveInfo.InstantAppDigest.UNDEFINED) {
                return getDigestPrefix();
            } else if (mDigestPrefixSecure == null) {
                // let's generate some random data to intersperse throughout the set of prefixes
                final int realSize = getDigestPrefix().length;
                final int manufacturedSize = realSize + 10 + sRandom.nextInt(10);
                mDigestPrefixSecure = Arrays.copyOf(getDigestPrefix(), manufacturedSize);
                for (int i = realSize; i < manufacturedSize; i++) {
                    mDigestPrefixSecure[i] = sRandom.nextInt() & DIGEST_MASK;
                }
                Arrays.sort(mDigestPrefixSecure);
            }
            return mDigestPrefixSecure;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            final boolean isUndefined = this == UNDEFINED;
            out.writeBoolean(isUndefined);
            if (isUndefined) {
                return;
            }
            if (mDigestBytes == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(mDigestBytes.length);
                for (int i = 0; i < mDigestBytes.length; i++) {
                    out.writeByteArray(mDigestBytes[i]);
                }
            }
            out.writeIntArray(mDigestPrefix);
            out.writeIntArray(mDigestPrefixSecure);
        }

        @SuppressWarnings("hiding")
        public static final @android.annotation.NonNull Parcelable.Creator<InstantAppDigest> CREATOR =
                new Parcelable.Creator<InstantAppDigest>() {
            @Override
            public InstantAppDigest createFromParcel(Parcel in) {
                if (in.readBoolean() /* is undefined */) {
                    return UNDEFINED;
                }
                return new InstantAppDigest(in);
            }
            @Override
            public InstantAppDigest[] newArray(int size) {
                return new InstantAppDigest[size];
            }
        };
    }
}
