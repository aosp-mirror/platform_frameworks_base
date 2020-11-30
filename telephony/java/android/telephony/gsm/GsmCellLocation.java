/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.telephony.gsm;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.telephony.CellLocation;

/**
 * Represents the cell location on a GSM phone.
 *
 * @deprecated use {@link android.telephony.CellIdentity CellIdentity}.
 */
@Deprecated
public class GsmCellLocation extends CellLocation {
    private int mLac;
    private int mCid;
    private int mPsc;

    /**
     * Empty constructor.  Initializes the LAC and CID to -1.
     */
    public GsmCellLocation() {
        mLac = -1;
        mCid = -1;
        mPsc = -1;
    }

    /**
     * Initialize the object from a bundle.
     */
    public GsmCellLocation(Bundle bundle) {
        mLac = bundle.getInt("lac", -1);
        mCid = bundle.getInt("cid", -1);
        mPsc = bundle.getInt("psc", -1);
    }

    /**
     * @return gsm location area code, -1 if unknown, 0xffff max legal value
     */
    public int getLac() {
        return mLac;
    }

    /**
     * @return gsm cell id, -1 if unknown or invalid, 0xffff max legal value
     */
    public int getCid() {
        return mCid;
    }

    /**
     * On a UMTS network, returns the primary scrambling code of the serving
     * cell.
     *
     * @return primary scrambling code for UMTS, -1 if unknown or GSM
     */
    public int getPsc() {
        return mPsc;
    }

    /**
     * Invalidate this object.  The location area code and the cell id are set to -1.
     */
    @Override
    public void setStateInvalid() {
        mLac = -1;
        mCid = -1;
        mPsc = -1;
    }

    /**
     * Set the location area code and the cell id.
     */
    public void setLacAndCid(int lac, int cid) {
        mLac = lac;
        mCid = cid;
    }

    /**
     * Set the primary scrambling code.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setPsc(int psc) {
        mPsc = psc;
    }

    @Override
    public int hashCode() {
        return mLac ^ mCid;
    }

    @Override
    public boolean equals(Object o) {
        GsmCellLocation s;

        try {
            s = (GsmCellLocation)o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return equalsHandlesNulls(mLac, s.mLac) && equalsHandlesNulls(mCid, s.mCid)
            && equalsHandlesNulls(mPsc, s.mPsc);
    }

    @Override
    public String toString() {
        return "["+ mLac + "," + mCid + "," + mPsc + "]";
    }

    /**
     * Test whether two objects hold the same data values or both are null
     *
     * @param a first obj
     * @param b second obj
     * @return true if two objects equal or both are null
     */
    private static boolean equalsHandlesNulls(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }

    /**
     * Set intent notifier Bundle based on service state
     *
     * @param m intent notifier Bundle
     */
    public void fillInNotifierBundle(Bundle m) {
        m.putInt("lac", mLac);
        m.putInt("cid", mCid);
        m.putInt("psc", mPsc);
    }

    /**
     * @hide
     */
    public boolean isEmpty() {
        return (mLac == -1 && mCid == -1 && mPsc == -1);
    }
}
