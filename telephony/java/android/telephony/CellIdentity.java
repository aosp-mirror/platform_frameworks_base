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
 * CellIdentity is to represent ONE unique cell in the world
 * it contains all levels of info to identity country, carrier, etc.
 *
 * @hide pending API review
 */
public abstract class CellIdentity implements Parcelable {

    // Cell is a GSM Cell {@link GsmCellIdentity}
    public static final int CELLID_TYPE_GSM = 1;
    // Cell is a CMDA Cell {@link CdmaCellIdentity}
    public static final int CELLID_TYPE_CDMA = 2;
    // Cell is a LTE Cell {@link LteCellIdentity}
    public static final int CELLID_TYPE_LTE = 3;

    private int mCellIdType;
    private String mCellIdAttributes;

    protected CellIdentity(int type, String attr) {
        this.mCellIdType = type;
        this.mCellIdAttributes = new String(attr);
    }

    protected CellIdentity(Parcel in) {
        this.mCellIdType = in.readInt();
        this.mCellIdAttributes = new String(in.readString());
    }

    protected CellIdentity(CellIdentity cid) {
        this.mCellIdType = cid.mCellIdType;
        this.mCellIdAttributes = new String(cid.mCellIdAttributes);
    }

    /**
     * @return Cell Identity type as one of CELLID_TYPE_XXXX
     */
    public int getCellIdType() {
        return mCellIdType;
    }


    /**
     * @return Cell identity attribute pairs
     * Comma separated “key=value” pairs.
     *   key := must must an single alpha-numeric word
     *   value := “quoted value string”
     *
     * Current list of keys and values:
     *   type = fixed | mobile
     */
    public String getCellIdAttributes() {
        return mCellIdAttributes;
    }


    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCellIdType);
        dest.writeString(mCellIdAttributes);
    }
}
