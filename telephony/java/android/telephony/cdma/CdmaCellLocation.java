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

package android.telephony.cdma;

import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.telephony.CellLocation;

/**
 * Represents the cell location on a CDMA phone.
 */
public class CdmaCellLocation extends CellLocation {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mBaseStationId = -1;

    /**
     * @hide
     */
    public final static int INVALID_LAT_LONG = Integer.MAX_VALUE;

    /**
     * Latitude is a decimal number as specified in 3GPP2 C.S0005-A v6.0.
     * It is represented in units of 0.25 seconds and ranges from -1296000
     * to 1296000, both values inclusive (corresponding to a range of -90
     * to +90 degrees). Integer.MAX_VALUE is considered invalid value.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mBaseStationLatitude = INVALID_LAT_LONG;

    /**
     * Longitude is a decimal number as specified in 3GPP2 C.S0005-A v6.0.
     * It is represented in units of 0.25 seconds and ranges from -2592000
     * to 2592000, both values inclusive (corresponding to a range of -180
     * to +180 degrees). Integer.MAX_VALUE is considered invalid value.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mBaseStationLongitude = INVALID_LAT_LONG;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mSystemId = -1;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mNetworkId = -1;

    /**
     * Empty constructor.
     * Initializes the BID, SID, NID and base station latitude and longitude
     * to invalid values.
     */
    public CdmaCellLocation() {
        this.mBaseStationId = -1;
        this.mBaseStationLatitude = INVALID_LAT_LONG;
        this.mBaseStationLongitude = INVALID_LAT_LONG;
        this.mSystemId = -1;
        this.mNetworkId = -1;
    }

    /**
     * Initialize the object from a bundle.
     */
    public CdmaCellLocation(Bundle bundle) {
        this.mBaseStationId = bundle.getInt("baseStationId", mBaseStationId);
        this.mBaseStationLatitude = bundle.getInt("baseStationLatitude", mBaseStationLatitude);
        this.mBaseStationLongitude = bundle.getInt("baseStationLongitude", mBaseStationLongitude);
        this.mSystemId = bundle.getInt("systemId", mSystemId);
        this.mNetworkId = bundle.getInt("networkId", mNetworkId);
    }

    /**
     * @return cdma base station identification number, -1 if unknown
     */
    public int getBaseStationId() {
        return this.mBaseStationId;
    }

    /**
     * Latitude is a decimal number as specified in 3GPP2 C.S0005-A v6.0.
     * (http://www.3gpp2.org/public_html/specs/C.S0005-A_v6.0.pdf)
     * It is represented in units of 0.25 seconds and ranges from -1296000
     * to 1296000, both values inclusive (corresponding to a range of -90
     * to +90 degrees). Integer.MAX_VALUE is considered invalid value.
     *
     * @return cdma base station latitude in units of 0.25 seconds, Integer.MAX_VALUE if unknown
     */
    public int getBaseStationLatitude() {
        return this.mBaseStationLatitude;
    }

    /**
     * Longitude is a decimal number as specified in 3GPP2 C.S0005-A v6.0.
     * (http://www.3gpp2.org/public_html/specs/C.S0005-A_v6.0.pdf)
     * It is represented in units of 0.25 seconds and ranges from -2592000
     * to 2592000, both values inclusive (corresponding to a range of -180
     * to +180 degrees). Integer.MAX_VALUE is considered invalid value.
     *
     * @return cdma base station longitude in units of 0.25 seconds, Integer.MAX_VALUE if unknown
     */
    public int getBaseStationLongitude() {
        return this.mBaseStationLongitude;
    }

    /**
     * @return cdma system identification number, -1 if unknown
     */
    public int getSystemId() {
        return this.mSystemId;
    }

    /**
     * @return cdma network identification number, -1 if unknown
     */
    public int getNetworkId() {
        return this.mNetworkId;
    }

    /**
     * Invalidate this object.  The cell location data is set to invalid values.
     */
    @Override
    public void setStateInvalid() {
        this.mBaseStationId = -1;
        this.mBaseStationLatitude = INVALID_LAT_LONG;
        this.mBaseStationLongitude = INVALID_LAT_LONG;
        this.mSystemId = -1;
        this.mNetworkId = -1;
    }

    /**
     * Set the cell location data.
     */
    public void setCellLocationData(int baseStationId, int baseStationLatitude,
         int baseStationLongitude) {
         // The following values have to be written in the correct sequence
         this.mBaseStationId = baseStationId;
         this.mBaseStationLatitude = baseStationLatitude;   //values[2];
         this.mBaseStationLongitude = baseStationLongitude; //values[3];
    }

    /**
     * Set the cell location data.
     */
     public void setCellLocationData(int baseStationId, int baseStationLatitude,
         int baseStationLongitude, int systemId, int networkId) {
         // The following values have to be written in the correct sequence
         this.mBaseStationId = baseStationId;
         this.mBaseStationLatitude = baseStationLatitude;   //values[2];
         this.mBaseStationLongitude = baseStationLongitude; //values[3];
         this.mSystemId = systemId;
         this.mNetworkId = networkId;
    }

    @Override
    public int hashCode() {
        return this.mBaseStationId ^ this.mBaseStationLatitude ^ this.mBaseStationLongitude
                ^ this.mSystemId ^ this.mNetworkId;
    }

    @Override
    public boolean equals(Object o) {
        CdmaCellLocation s;

        try {
            s = (CdmaCellLocation)o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (equalsHandlesNulls(this.mBaseStationId, s.mBaseStationId) &&
                equalsHandlesNulls(this.mBaseStationLatitude, s.mBaseStationLatitude) &&
                equalsHandlesNulls(this.mBaseStationLongitude, s.mBaseStationLongitude) &&
                equalsHandlesNulls(this.mSystemId, s.mSystemId) &&
                equalsHandlesNulls(this.mNetworkId, s.mNetworkId)
        );
    }

    @Override
    public String toString() {
        return "[" + this.mBaseStationId + ","
                   + this.mBaseStationLatitude + ","
                   + this.mBaseStationLongitude + ","
                   + this.mSystemId + ","
                   + this.mNetworkId + "]";
    }

    /**
     * Test whether two objects hold the same data values or both are null
     *
     * @param a first obj
     * @param b second obj
     * @return true if two objects equal or both are null
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static boolean equalsHandlesNulls(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }

    /**
     * Fill the cell location data into the intent notifier Bundle based on service state
     *
     * @param bundleToFill intent notifier Bundle
     */
    public void fillInNotifierBundle(Bundle bundleToFill) {
        bundleToFill.putInt("baseStationId", this.mBaseStationId);
        bundleToFill.putInt("baseStationLatitude", this.mBaseStationLatitude);
        bundleToFill.putInt("baseStationLongitude", this.mBaseStationLongitude);
        bundleToFill.putInt("systemId", this.mSystemId);
        bundleToFill.putInt("networkId", this.mNetworkId);
    }

    /**
     * @hide
     */
    public boolean isEmpty() {
        return (this.mBaseStationId == -1 &&
                this.mBaseStationLatitude == INVALID_LAT_LONG &&
                this.mBaseStationLongitude == INVALID_LAT_LONG &&
                this.mSystemId == -1 &&
                this.mNetworkId == -1);
    }

    /**
     * Converts latitude or longitude from 0.25 seconds (as defined in the
     * 3GPP2 C.S0005-A v6.0 standard) to decimal degrees
     *
     * @param quartSec latitude or longitude in 0.25 seconds units
     * @return latitude or longitude in decimal degrees units
     * @throws IllegalArgumentException if value is less than -2592000,
     *                                  greater than 2592000, or is not a number.
     */
    public static double convertQuartSecToDecDegrees(int quartSec) {
        if(Double.isNaN(quartSec) || quartSec < -2592000 || quartSec > 2592000){
            // Invalid value
            throw new IllegalArgumentException("Invalid coordiante value:" + quartSec);
        }
        return ((double)quartSec) / (3600 * 4);
    }

}


