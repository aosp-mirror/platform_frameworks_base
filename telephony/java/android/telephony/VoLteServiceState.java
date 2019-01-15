/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Rlog;

/**
 * Contains LTE network state related information.
 * @deprecated Only contains SRVCC state, which isn't specific to LTE handovers. For SRVCC
 * indications, use {@link PhoneStateListener#onSrvccStateChanged(int)}.
 * @hide
 */
@Deprecated
public final class VoLteServiceState implements Parcelable {

    private static final String LOG_TAG = "VoLteServiceState";
    private static final boolean DBG = false;

    //Use int max, as -1 is a valid value in signal strength
    public static final int INVALID = 0x7FFFFFFF;

    public static final int NOT_SUPPORTED = 0;
    public static final int SUPPORTED = 1;

    // Single Radio Voice Call Continuity(SRVCC) progress state
    public static final int HANDOVER_STARTED   = 0;
    public static final int HANDOVER_COMPLETED = 1;
    public static final int HANDOVER_FAILED    = 2;
    public static final int HANDOVER_CANCELED  = 3;

    private int mSrvccState;

    /**
     * Create a new VoLteServiceState from a intent notifier Bundle
     *
     * This method is used by PhoneStateIntentReceiver and maybe by
     * external applications.
     *
     * @param m Bundle from intent notifier
     * @return newly created VoLteServiceState
     *
     * @hide
     */
    public static VoLteServiceState newFromBundle(Bundle m) {
        VoLteServiceState ret;
        ret = new VoLteServiceState();
        ret.setFromNotifierBundle(m);
        return ret;
    }

    /**
     * Empty constructor
     *
     * @hide
     */
    public VoLteServiceState() {
        initialize();
    }

    /**
     * Constructor
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public VoLteServiceState(int srvccState) {
        initialize();

        mSrvccState = srvccState;
    }

    /**
     * Copy constructors
     *
     * @param s Source VoLteServiceState
     *
     * @hide
     */
    public VoLteServiceState(VoLteServiceState s) {
        copyFrom(s);
    }

    /**
     * Initialize values to defaults.
     *
     * @hide
     */
    private void initialize() {
        mSrvccState = INVALID;
    }

    /**
     * @hide
     */
    protected void copyFrom(VoLteServiceState s) {
        mSrvccState = s.mSrvccState;
    }

    /**
     * Construct a VoLteServiceState object from the given parcel.
     *
     * @hide
     */
    public VoLteServiceState(Parcel in) {
        if (DBG) log("Size of VoLteServiceState parcel:" + in.dataSize());

        mSrvccState = in.readInt();
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mSrvccState);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable.Creator}
     *
     * @hide
     */
    public static final Parcelable.Creator<VoLteServiceState> CREATOR = new Parcelable.Creator() {
        public VoLteServiceState createFromParcel(Parcel in) {
            return new VoLteServiceState(in);
        }

        public VoLteServiceState[] newArray(int size) {
            return new VoLteServiceState[size];
        }
    };

    /**
     * Validate the individual fields as per the range
     * specified in ril.h
     * Set to invalid any field that is not in the valid range
     *
     * @return
     *      Valid values for all fields
     * @hide
     */
    public void validateInput() {
    }

    public int hashCode() {
        int primeNum = 31;
        return ((mSrvccState * primeNum));
    }

    /**
     * @return true if the LTE network states are the same
     */
    @Override
    public boolean equals (Object o) {
        VoLteServiceState s;

        try {
            s = (VoLteServiceState) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (mSrvccState == s.mSrvccState);
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return ("VoLteServiceState:"
                + " " + mSrvccState);
    }

    /**
     * Set VoLteServiceState based on intent notifier map
     *
     * @param m intent notifier map
     * @hide
     */
    private void setFromNotifierBundle(Bundle m) {
        mSrvccState = m.getInt("mSrvccState");
    }

    /**
     * Set intent notifier Bundle based on VoLteServiceState
     *
     * @param m intent notifier Bundle
     * @hide
     */
    public void fillInNotifierBundle(Bundle m) {
        m.putInt("mSrvccState", mSrvccState);
    }

    public int getSrvccState() {
        return mSrvccState;
    }

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
