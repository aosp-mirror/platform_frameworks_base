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
import android.annotation.SystemApi;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Information about an ephemeral application.
 * @hide
 */
@SystemApi
public final class EphemeralResolveInfo implements Parcelable {
    /** Algorithm that will be used to generate the domain digest */
    public static final String SHA_ALGORITHM = "SHA-256";

    /** Full digest of the domain hash */
    private final byte[] mDigestBytes;
    /** The first 4 bytes of the domain hash */
    private final int mDigestPrefix;
    private final String mPackageName;
    /** The filters used to match domain */
    private final List<IntentFilter> mFilters = new ArrayList<IntentFilter>();

    public EphemeralResolveInfo(@NonNull Uri uri, @NonNull String packageName,
            @NonNull List<IntentFilter> filters) {
        // validate arguments
        if (uri == null
                || packageName == null
                || filters == null
                || filters.size() == 0) {
            throw new IllegalArgumentException();
        }

        mDigestBytes = generateDigest(uri);
        mDigestPrefix =
                mDigestBytes[0] << 24
                | mDigestBytes[1] << 16
                | mDigestBytes[2] << 8
                | mDigestBytes[3] << 0;
        mFilters.addAll(filters);
        mPackageName = packageName;
    }

    EphemeralResolveInfo(Parcel in) {
        mDigestBytes = in.createByteArray();
        mDigestPrefix = in.readInt();
        mPackageName = in.readString();
        in.readList(mFilters, null /*loader*/);
    }

    public byte[] getDigestBytes() {
        return mDigestBytes;
    }

    public int getDigestPrefix() {
        return mDigestPrefix;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public List<IntentFilter> getFilters() {
        return mFilters;
    }

    private static byte[] generateDigest(Uri uri) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(SHA_ALGORITHM);
            final byte[] hostBytes = uri.getHost().getBytes();
            return digest.digest(hostBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("could not find digest algorithm");
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeByteArray(mDigestBytes);
        out.writeInt(mDigestPrefix);
        out.writeString(mPackageName);
        out.writeList(mFilters);
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

    /** @hide */
    public static final class EphemeralResolveIntentInfo extends IntentFilter {
        private final EphemeralResolveInfo mResolveInfo;

        public EphemeralResolveIntentInfo(@NonNull IntentFilter orig,
                @NonNull EphemeralResolveInfo resolveInfo) {
            super(orig);
            this.mResolveInfo = resolveInfo;
        }

        public EphemeralResolveInfo getEphemeralResolveInfo() {
            return mResolveInfo;
        }
    }
}
