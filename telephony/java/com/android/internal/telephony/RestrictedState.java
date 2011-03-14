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

package com.android.internal.telephony;

import android.telephony.ServiceState;

public class RestrictedState {

    /**
     * Set true to block packet data access due to restriction
     */
    private boolean mPsRestricted;
    /**
     * Set true to block all normal voice/SMS/USSD/SS/AV64 due to restriction
     */
    private boolean mCsNormalRestricted;
    /**
     * Set true to block emergency call due to restriction
     */
    private boolean mCsEmergencyRestricted;

    public RestrictedState() {
        setPsRestricted(false);
        setCsNormalRestricted(false);
        setCsEmergencyRestricted(false);
    }

    /**
     * @param csEmergencyRestricted the csEmergencyRestricted to set
     */
    public void setCsEmergencyRestricted(boolean csEmergencyRestricted) {
        mCsEmergencyRestricted = csEmergencyRestricted;
    }

    /**
     * @return the csEmergencyRestricted
     */
    public boolean isCsEmergencyRestricted() {
        return mCsEmergencyRestricted;
    }

    /**
     * @param csNormalRestricted the csNormalRestricted to set
     */
    public void setCsNormalRestricted(boolean csNormalRestricted) {
        mCsNormalRestricted = csNormalRestricted;
    }

    /**
     * @return the csNormalRestricted
     */
    public boolean isCsNormalRestricted() {
        return mCsNormalRestricted;
    }

    /**
     * @param psRestricted the psRestricted to set
     */
    public void setPsRestricted(boolean psRestricted) {
        mPsRestricted = psRestricted;
    }

    /**
     * @return the psRestricted
     */
    public boolean isPsRestricted() {
        return mPsRestricted;
    }

    public boolean isCsRestricted() {
        return mCsNormalRestricted && mCsEmergencyRestricted;
    }

    @Override
    public boolean equals (Object o) {
        RestrictedState s;

        try {
            s = (RestrictedState) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return mPsRestricted == s.mPsRestricted
        && mCsNormalRestricted == s.mCsNormalRestricted
        && mCsEmergencyRestricted == s.mCsEmergencyRestricted;
    }

    @Override
    public String toString() {
        String csString = "none";

        if (mCsEmergencyRestricted && mCsNormalRestricted) {
            csString = "all";
        } else if (mCsEmergencyRestricted && !mCsNormalRestricted) {
            csString = "emergency";
        } else if (!mCsEmergencyRestricted && mCsNormalRestricted) {
            csString = "normal call";
        }

        return  "Restricted State CS: " + csString + " PS:" + mPsRestricted;
    }

}
