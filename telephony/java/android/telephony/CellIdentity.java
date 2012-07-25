/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * CellIdentity is immutable and represents ONE unique cell in the world
 * it contains all levels of info to identity country, carrier, etc.
 *
 * @hide
 */
public abstract class CellIdentity implements Parcelable {

    // Type fields for parceling
    protected static final int TYPE_GSM = 1;
    protected static final int TYPE_CDMA = 2;
    protected static final int TYPE_LTE = 3;

    protected CellIdentity() {
    }

    protected CellIdentity(Parcel in) {
    }

    protected CellIdentity(CellIdentity cid) {
    }

    /**
     * @return a copy of this object with package visibility.
     */
    abstract CellIdentity copy();

    @Override
    public abstract int hashCode();

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (this == other) {
            return true;
        }
        return (other instanceof CellIdentity);
    }

    @Override
    public String toString() {
        return "";
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }

    /** Implement the Parcelable interface */
    public static final Creator<CellIdentity> CREATOR =
            new Creator<CellIdentity>() {
        @Override
        public CellIdentity createFromParcel(Parcel in) {
            int type = in.readInt();
            switch (type) {
                case TYPE_GSM: return CellIdentityGsm.createFromParcelBody(in);
                case TYPE_CDMA: return CellIdentityCdma.createFromParcelBody(in);
                case TYPE_LTE: return CellIdentityLte.createFromParcelBody(in);
                default: throw new RuntimeException("Bad CellIdentity Parcel");
            }
        }

        @Override
        public CellIdentity[] newArray(int size) {
            return new CellIdentity[size];
        }
    };
}
