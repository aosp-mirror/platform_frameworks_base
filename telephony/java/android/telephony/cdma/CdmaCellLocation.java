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

import android.os.Bundle;
import android.telephony.CellLocation;

/**
 * Represents the cell location on a GSM phone.
 * @hide
 */
public class CdmaCellLocation extends CellLocation {
    private int mBaseStationId = -1;
    private int mBaseStationLatitude = -1;
    private int mBaseStationLongitude = -1;
    private int mSystemId = -1;
    private int mNetworkId = -1;

    /**
     * Empty constructor.  Initializes the LAC and CID to -1.
     */
    public CdmaCellLocation() {
        this.mBaseStationId = -1;
        this.mBaseStationLatitude = -1;
        this.mBaseStationLongitude = -1;
        this.mSystemId = -1;
        this.mNetworkId = -1;
    }

    /**
     * Initialize the object from a bundle.
     */
    public CdmaCellLocation(Bundle bundleWithValues) {
        this.mBaseStationId = bundleWithValues.getInt("baseStationId");
        this.mBaseStationLatitude = bundleWithValues.getInt("baseStationLatitude");
        this.mBaseStationLongitude = bundleWithValues.getInt("baseStationLongitude");
        this.mSystemId = bundleWithValues.getInt("systemId");
        this.mNetworkId = bundleWithValues.getInt("networkId");
    }

    /**
     * @return cdma base station identification number, -1 if unknown
     */
    public int getBaseStationId() {
        return this.mBaseStationId;
    }

    /**
     * @return cdma base station latitude, -1 if unknown
     */
    public int getBaseStationLatitude() {
        return this.mBaseStationLatitude;
    }

    /**
     * @return cdma base station longitude, -1 if unknown
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
     * Invalidate this object.  The cell location data is set to -1.
     */
    public void setStateInvalid() {
        this.mBaseStationId = -1;
        this.mBaseStationLatitude = -1;
        this.mBaseStationLongitude = -1;
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

}


