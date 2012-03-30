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
 * CellIdentity is to represent a unique LTE cell
 *
 * @hide pending API review
 */
public final class LteCellIdentity extends CellIdentity implements Parcelable {

    // 3-digit Mobile Country Code, 0..999
    private final int mMcc;
    // 2 or 3-digit Mobile Network Code, 0..999
    private final int mMnc;
    // 28-bit cell identity
    private final int mCi;
    // physical cell id 0..503
    private final int mPci;
    // 16-bit tracking area code
    private final int mTac;

    /**
     *
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param ci 28-bit Cell Identity
     * @param pci Physical Cell Id 0..503
     * @param tac 16-bit Tracking Area Code
     * @param attr is comma separated “key=value” attribute pairs.
     */
    public LteCellIdentity (int mcc, int mnc,
            int ci, int pci, int tac, String attr) {
        super(CELLID_TYPE_CDMA, attr);
        mMcc = mcc;
        mMnc = mnc;
        mCi = ci;
        mPci = pci;
        mTac = tac;
    }

    private LteCellIdentity(Parcel in) {
        super(in);
        mMcc = in.readInt();
        mMnc = in.readInt();
        mCi = in.readInt();
        mPci = in.readInt();
        mTac = in.readInt();
    }

    LteCellIdentity(LteCellIdentity cid) {
        super(cid);
        mMcc = cid.mMcc;
        mMnc = cid.mMnc;
        mCi = cid.mCi;
        mPci = cid.mPci;
        mTac = cid.mTac;
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
     * @return 28-bit Cell Identity
     */
    public int getCi() {
        return mCi;
    }

    /**
     * @return Physical Cell Id 0..503
     */
    public int getPci() {
        return mPci;
    }

    /**
     * @return 16-bit Tracking Area Code
     */
    public int getTac() {
        return mTac;
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
        dest.writeInt(mCi);
        dest.writeInt(mPci);
        dest.writeInt(mTac);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<LteCellIdentity> CREATOR =
            new Creator<LteCellIdentity>() {
        @Override
        public LteCellIdentity createFromParcel(Parcel in) {
            return new LteCellIdentity(in);
        }

        @Override
        public LteCellIdentity[] newArray(int size) {
            return new LteCellIdentity[size];
        }
    };
}
