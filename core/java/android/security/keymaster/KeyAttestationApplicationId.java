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

/**
 * @hide
 * The information aggregated by this class is used by keystore to identify a caller of the
 * keystore API toward a remote party. It aggregates multiple PackageInfos because keystore
 * can only determine a caller by uid granularity, and a uid can be shared by multiple packages.
 * The remote party must decide if it trusts all of the packages enough to consider the
 * confidentiality of the key material in question intact.
 */
public class KeyAttestationApplicationId implements Parcelable {
    private final KeyAttestationPackageInfo[] mAttestationPackageInfos;

    /**
     * @param mAttestationPackageInfos
     */
    public KeyAttestationApplicationId(KeyAttestationPackageInfo[] mAttestationPackageInfos) {
        super();
        this.mAttestationPackageInfos = mAttestationPackageInfos;
    }

    /**
     * @return the mAttestationPackageInfos
     */
    public KeyAttestationPackageInfo[] getAttestationPackageInfos() {
        return mAttestationPackageInfos;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedArray(mAttestationPackageInfos, flags);
    }

    public static final Parcelable.Creator<KeyAttestationApplicationId> CREATOR
            = new Parcelable.Creator<KeyAttestationApplicationId>() {
        @Override
        public KeyAttestationApplicationId createFromParcel(Parcel source) {
            return new KeyAttestationApplicationId(source);
        }

        @Override
        public KeyAttestationApplicationId[] newArray(int size) {
            return new KeyAttestationApplicationId[size];
        }
    };

    KeyAttestationApplicationId(Parcel source) {
        mAttestationPackageInfos = source.createTypedArray(KeyAttestationPackageInfo.CREATOR);
    }
}
