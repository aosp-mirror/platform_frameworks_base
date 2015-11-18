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

package com.android.internal.app;

import android.content.IntentFilter;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Information that is returned when resolving ephemeral
 * applications.
 */
public final class EphemeralResolveInfo implements Parcelable {
    public static final String SHA_ALGORITHM = "SHA-256";
    private byte[] mDigestBytes;
    private int mDigestPrefix;
    private final List<IntentFilter> mFilters = new ArrayList<IntentFilter>();

    public EphemeralResolveInfo(Uri uri, List<IntentFilter> filters) {
        generateDigest(uri);
        mFilters.addAll(filters);
    }

    private EphemeralResolveInfo(Parcel in) {
        readFromParcel(in);
    }

    public byte[] getDigestBytes() {
        return mDigestBytes;
    }

    public int getDigestPrefix() {
        return mDigestPrefix;
    }

    public List<IntentFilter> getFilters() {
        return mFilters;
    }

    private void generateDigest(Uri uri) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(SHA_ALGORITHM);
            final byte[] hostBytes = uri.getHost().getBytes();
            final byte[] digestBytes = digest.digest(hostBytes);
            mDigestBytes = digestBytes;
            mDigestPrefix =
                    digestBytes[0] << 24
                    | digestBytes[1] << 16
                    | digestBytes[2] << 8
                    | digestBytes[3] << 0;
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
        if (mDigestBytes == null) {
            out.writeInt(0);
        } else {
            out.writeInt(mDigestBytes.length);
            out.writeByteArray(mDigestBytes);
        }
        out.writeInt(mDigestPrefix);
        out.writeList(mFilters);
    }

    private void readFromParcel(Parcel in) {
        int digestBytesSize = in.readInt();
        if (digestBytesSize > 0) {
            mDigestBytes = new byte[digestBytesSize];
            in.readByteArray(mDigestBytes);
        }
        mDigestPrefix = in.readInt();
        in.readList(mFilters, null /*loader*/);
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
}
