/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.security.keymaster;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for the Java side of keystore-generated certificate chains.
 *
 * Serialization code for this must be kept in sync with system/security/keystore
 * @hide
 */
public class KeymasterCertificateChain implements Parcelable {

    private List<byte[]> mCertificates;

    public static final Parcelable.Creator<KeymasterCertificateChain> CREATOR = new
            Parcelable.Creator<KeymasterCertificateChain>() {
                public KeymasterCertificateChain createFromParcel(Parcel in) {
                    return new KeymasterCertificateChain(in);
                }
                public KeymasterCertificateChain[] newArray(int size) {
                    return new KeymasterCertificateChain[size];
                }
            };

    public KeymasterCertificateChain() {
        mCertificates = null;
    }

    public KeymasterCertificateChain(List<byte[]> mCertificates) {
        this.mCertificates = mCertificates;
    }

    private KeymasterCertificateChain(Parcel in) {
        readFromParcel(in);
    }

    public List<byte[]> getCertificates() {
        return mCertificates;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (mCertificates == null) {
            out.writeInt(0);
        } else {
            out.writeInt(mCertificates.size());
            for (byte[] arg : mCertificates) {
                out.writeByteArray(arg);
            }
        }
    }

    public void readFromParcel(Parcel in) {
        int length = in.readInt();
        mCertificates = new ArrayList<byte[]>(length);
        for (int i = 0; i < length; i++) {
            mCertificates.add(in.createByteArray());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
