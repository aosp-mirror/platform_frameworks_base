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

package android.telephony;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import com.android.internal.telephony.Phone;

/**
 * Contains phone state and service related information.
 *
 * The following phone information is included in returned ServiceState:
 *
 * <ul>
 *   <li>Service state: IN_SERVICE, OUT_OF_SERVICE, EMERGENCY_ONLY, POWER_OFF
 *   <li>Roaming indicator
 *   <li>Operator name, short name and numeric id
 *   <li>Network selection mode
 * </ul>
 */
public class ServiceState implements Parcelable {

    /**
     * Normal operation condition, the phone is registered
     * with an operator either in home network or in roaming.
     */
    public static final int STATE_IN_SERVICE = 0;

    /**
     * Phone is not registered with any operator, the phone
     * can be currently searching a new operator to register to, or not
     * searching to registration at all, or registration is denied, or radio
     * signal is not available.
     */
    public static final int STATE_OUT_OF_SERVICE = 1;

    /**
     * The phone is registered and locked.  Only emergency numbers are allowed. {@more}
     */
    public static final int STATE_EMERGENCY_ONLY = 2;

    /**
     * Radio of telephony is explictly powered off.
     */
    public static final int STATE_POWER_OFF = 3;

    private int mState = STATE_OUT_OF_SERVICE;
    private boolean mRoaming;
    private String mOperatorAlphaLong;
    private String mOperatorAlphaShort;
    private String mOperatorNumeric;
    private boolean mIsManualNetworkSelection;

    /**
     * Create a new ServiceState from a intent notifier Bundle
     *
     * This method is used by PhoneStateIntentReceiver and maybe by
     * external applications.
     *
     * @param m Bundle from intent notifier
     * @return newly created ServiceState
     * @hide
     */
    public static ServiceState newFromBundle(Bundle m) {
        ServiceState ret;
        ret = new ServiceState();
        ret.setFromNotifierBundle(m);
        return ret;
    }

    /**
     * Empty constructor
     */
    public ServiceState() {
    }

    /**
     * Copy constructors
     *
     * @param s Source service state
     */
    public ServiceState(ServiceState s) {
        copyFrom(s);
    }

    protected void copyFrom(ServiceState s) {
        mState = s.mState;
        mRoaming = s.mRoaming;
        mOperatorAlphaLong = s.mOperatorAlphaLong;
        mOperatorAlphaShort = s.mOperatorAlphaShort;
        mOperatorNumeric = s.mOperatorNumeric;
        mIsManualNetworkSelection = s.mIsManualNetworkSelection;
    }

    /**
     * Construct a ServiceState object from the given parcel.
     */
    public ServiceState(Parcel in) {
        mState = in.readInt();
        mRoaming = in.readInt() != 0;
        mOperatorAlphaLong = in.readString();
        mOperatorAlphaShort = in.readString();
        mOperatorNumeric = in.readString();
        mIsManualNetworkSelection = in.readInt() != 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mState);
        out.writeInt(mRoaming ? 1 : 0);
        out.writeString(mOperatorAlphaLong);
        out.writeString(mOperatorAlphaShort);
        out.writeString(mOperatorNumeric);
        out.writeInt(mIsManualNetworkSelection ? 1 : 0);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<ServiceState> CREATOR = new Parcelable.Creator() {
        public ServiceState createFromParcel(Parcel in) {
            return new ServiceState(in);
        }

        public ServiceState[] newArray(int size) {
            return new ServiceState[size];
        }
    };

    /**
     * Get current servcie state of phone
     *
     * @see #STATE_IN_SERVICE
     * @see #STATE_OUT_OF_SERVICE
     * @see #STATE_EMERGENCY_ONLY
     * @see #STATE_POWER_OFF
     */
    public int getState() {
        return mState;
    }

    /**
     * Get current roaming indicator of phone
     * (note: not just decoding from TS 27.007 7.2)
     *
     * @return true if TS 27.007 7.2 roaming is true
     *              and ONS is different from SPN
     *
     */
    public boolean getRoaming() {
        return mRoaming;
    }

    /**
     * Get current registered operator name in long alphanumeric format
     *
     * In GSM/UMTS, long format can be upto 16 characters long
     *
     * @return long name of operator, null if unregistered or unknown
     */
    public String getOperatorAlphaLong() {
        return mOperatorAlphaLong;
    }

    /**
     * Get current registered operator name in short lphanumeric format
     *
     * In GSM/UMST, short format can be upto 8 characters long
     *
     * @return short name of operator, null if unregistered or unknown
     */
    public String getOperatorAlphaShort() {
        return mOperatorAlphaShort;
    }

    /**
     * Get current registered operator numeric id
     *
     * In GSM/UMTS, numeric format is 3 digit country code plus 2 or 3 digit
     * network code
     *
     * The country code can be decoded using MccTable.countryCodeForMcc()
     *
     * @return numeric format of operator, null if unregistered or unknown
     */
    public String getOperatorNumeric() {
        return mOperatorNumeric;
    }

    /**
     * Get current network selection mode
     *
     * @return true if manual mode, false if automatic mode
     */
    public boolean getIsManualSelection() {
        return mIsManualNetworkSelection;
    }

    @Override
    public int hashCode() {
        return (mState * 0x1234)
                + (mRoaming ? 1 : 0)
                + (mIsManualNetworkSelection ? 1 : 0)
                + ((null == mOperatorAlphaLong) ? 0 : mOperatorAlphaLong.hashCode())
                + ((null == mOperatorAlphaShort) ? 0 : mOperatorAlphaShort.hashCode())
                + ((null == mOperatorNumeric) ? 0 : mOperatorNumeric.hashCode());
    }

    @Override
    public boolean equals (Object o) {
        ServiceState s;
        
        try {
            s = (ServiceState) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return mState == s.mState
                && mRoaming == s.mRoaming
                && mIsManualNetworkSelection == s.mIsManualNetworkSelection
                && equalsHandlesNulls(mOperatorAlphaLong, s.mOperatorAlphaLong)
                && equalsHandlesNulls(mOperatorAlphaShort, s.mOperatorAlphaShort)
                && equalsHandlesNulls(mOperatorNumeric, s.mOperatorNumeric);
    }

    @Override
    public String toString() {
        return mState + " " + (mRoaming ? "roaming" : "home")
                + " " + mOperatorAlphaLong
                + " " + mOperatorAlphaShort
                + " " + mOperatorNumeric
                + " " + (mIsManualNetworkSelection ? "(manual)" : "");
    }

    public void setStateOutOfService() {
        mState = STATE_OUT_OF_SERVICE;
        mRoaming = false;
        mOperatorAlphaLong = null;
        mOperatorAlphaShort = null;
        mOperatorNumeric = null;
        mIsManualNetworkSelection = false;
    }

    public void setStateOff() {
        mState = STATE_POWER_OFF;
        mRoaming = false;
        mOperatorAlphaLong = null;
        mOperatorAlphaShort = null;
        mOperatorNumeric = null;
        mIsManualNetworkSelection = false;
    }

    public void setState(int state) {
        mState = state;
    }

    public void setRoaming(boolean roaming) {
        mRoaming = roaming;
    }

    public void setOperatorName(String longName, String shortName, String numeric) {
        mOperatorAlphaLong = longName;
        mOperatorAlphaShort = shortName;
        mOperatorNumeric = numeric;
    }

    public void setIsManualSelection(boolean isManual) {
        mIsManualNetworkSelection = isManual;
    }
    
    /**
     * Test whether two objects hold the same data values or both are null
     *
     * @param a first obj
     * @param b second obj
     * @return true if two objects equal or both are null
     */
    private static boolean equalsHandlesNulls (Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }

    /**
     * Set ServiceState based on intent notifier map
     *
     * @param m intent notifier map
     * @hide
     */
    private void setFromNotifierBundle(Bundle m) {
        mState = m.getInt("state");
        mRoaming = m.getBoolean("roaming");
        mOperatorAlphaLong = m.getString("operator-alpha-long");
        mOperatorAlphaShort = m.getString("operator-alpha-short");
        mOperatorNumeric = m.getString("operator-numeric");
        mIsManualNetworkSelection = m.getBoolean("manual");
    }

    /**
     * Set intent notifier Bundle based on service state
     *
     * @param m intent notifier Bundle
     * @hide
     */
    public void fillInNotifierBundle(Bundle m) {
        m.putInt("state", mState);
        m.putBoolean("roaming", Boolean.valueOf(mRoaming));
        m.putString("operator-alpha-long", mOperatorAlphaLong);
        m.putString("operator-alpha-short", mOperatorAlphaShort);
        m.putString("operator-numeric", mOperatorNumeric);
        m.putBoolean("manual", Boolean.valueOf(mIsManualNetworkSelection));
    }
}
