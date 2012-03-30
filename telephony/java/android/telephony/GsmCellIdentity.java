/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * CellIdentity to represent a unique GSM or UMTS cell
 *
 * @hide pending API review
 */
public final class GsmCellIdentity extends CellIdentity implements Parcelable {

    // 3-digit Mobile Country Code, 0..999
    private final int mMcc;
    // 2 or 3-digit Mobile Network Code, 0..999
    private final int mMnc;
    // 16-bit Location Area Code, 0..65535
    private final int mLac;
    // 16-bit GSM Cell Identity described in TS 27.007, 0..65535
    // 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455
    private final int mCid;
    // 9-bit UMTS Primary Scrambling Code described in TS 25.331, 0..511
    private final int mPsc;

    /**
     * public constructor
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param lac 16-bit Location Area Code, 0..65535
     * @param cid 16-bit GSM Cell Identity or 28-bit UMTS Cell Identity
     * @param psc 9-bit UMTS Primary Scrambling Code
     * @param attr is comma separated “key=value” attribute pairs.
     */
    public GsmCellIdentity (int mcc, int mnc,
            int lac, int cid, int psc, String attr) {
        super(CELLID_TYPE_GSM, attr);
        mMcc = mcc;
        mMnc = mnc;
        mLac = lac;
        mCid = cid;
        mPsc = psc;
    }

    private GsmCellIdentity(Parcel in) {
        super(in);
        mMcc = in.readInt();
        mMnc = in.readInt();
        mLac = in.readInt();
        mCid = in.readInt();
        mPsc = in.readInt();
    }

    GsmCellIdentity(GsmCellIdentity cid) {
        super(cid);
        mMcc = cid.mMcc;
        mMnc = cid.mMnc;
        mLac = cid.mLac;
        mCid = cid.mCid;
        mPsc = cid.mPsc;
    }

    /**
     * @return 3-digit Mobile Country Code, 0..999
     */
    public int getMcc() {
        return mMcc;
    }

    /**
     * @return 2 or 3-digit Mobile Network Code, 0..999
     */
    public int getMnc() {
        return mMnc;
    }

    /**
     * @return 16-bit Location Area Code, 0..65535
     */
    public int getLac() {
        return mLac;
    }

    /**
     * @return CID
     * Either 16-bit GSM Cell Identity described
     * in TS 27.007, 0..65535
     * or 28-bit UMTS Cell Identity described
     * in TS 25.331, 0..268435455
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return 9-bit UMTS Primary Scrambling Code described in
     * TS 25.331, 0..511
     */
    public int getPsc() {
        return mPsc;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mMcc);
        dest.writeInt(mMnc);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mPsc);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<GsmCellIdentity> CREATOR =
            new Creator<GsmCellIdentity>() {
        @Override
        public GsmCellIdentity createFromParcel(Parcel in) {
            return new GsmCellIdentity(in);
        }

        @Override
        public GsmCellIdentity[] newArray(int size) {
            return new GsmCellIdentity[size];
        }
    };
}
