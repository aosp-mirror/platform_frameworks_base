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

import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 * This class constitutes and excerpt from the PackageManager's PackageInfo for the purpose of
 * key attestation. It is part of the KeyAttestationApplicationId, which is used by
 * keystore to identify the caller of the keystore API towards a remote party.
 */
public class KeyAttestationPackageInfo implements Parcelable {
    private final String mPackageName;
    private final int mPackageVersionCode;
    private final Signature[] mPackageSignatures;

    /**
     * @param mPackageName
     * @param mPackageVersionCode
     * @param mPackageSignatures
     */
    public KeyAttestationPackageInfo(
            String mPackageName, int mPackageVersionCode, Signature[] mPackageSignatures) {
        super();
        this.mPackageName = mPackageName;
        this.mPackageVersionCode = mPackageVersionCode;
        this.mPackageSignatures = mPackageSignatures;
    }
    /**
     * @return the mPackageName
     */
    public String getPackageName() {
        return mPackageName;
    }
    /**
     * @return the mPackageVersionCode
     */
    public int getPackageVersionCode() {
        return mPackageVersionCode;
    }
    /**
     * @return the mPackageSignatures
     */
    public Signature[] getPackageSignatures() {
        return mPackageSignatures;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeInt(mPackageVersionCode);
        dest.writeTypedArray(mPackageSignatures, flags);
    }

    public static final Parcelable.Creator<KeyAttestationPackageInfo> CREATOR
            = new Parcelable.Creator<KeyAttestationPackageInfo>() {
        @Override
        public KeyAttestationPackageInfo createFromParcel(Parcel source) {
            return new KeyAttestationPackageInfo(source);
        }

        @Override
        public KeyAttestationPackageInfo[] newArray(int size) {
            return new KeyAttestationPackageInfo[size];
        }
    };

    private KeyAttestationPackageInfo(Parcel source) {
        mPackageName = source.readString();
        mPackageVersionCode = source.readInt();
        mPackageSignatures = source.createTypedArray(Signature.CREATOR);
    }
}
