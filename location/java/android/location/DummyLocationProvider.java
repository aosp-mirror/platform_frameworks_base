/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.location;

/**
 * A stub implementation of LocationProvider used by LocationManager.
 * A DummyLocationProvider may be queried to determine the properties
 * of the provider whcih it shadows, but does not actually provide location
 * data.
 * 
 * {@hide}
 */
class DummyLocationProvider extends LocationProvider {

    private static final String TAG = "DummyLocationProvider";

    String mName;
    boolean mRequiresNetwork;
    boolean mRequiresSatellite;
    boolean mRequiresCell;
    boolean mHasMonetaryCost;
    boolean mSupportsAltitude;
    boolean mSupportsSpeed;
    boolean mSupportsBearing;
    int mPowerRequirement;
    int mAccuracy;

    /* package */ DummyLocationProvider(String name) {
        super(name);
    }

    public void setRequiresNetwork(boolean requiresNetwork) {
        mRequiresNetwork = requiresNetwork;
    }

    public void setRequiresSatellite(boolean requiresSatellite) {
        mRequiresSatellite = requiresSatellite;
    }

    public void setRequiresCell(boolean requiresCell) {
        mRequiresCell = requiresCell;
    }

    public void setHasMonetaryCost(boolean hasMonetaryCost) {
        mHasMonetaryCost = hasMonetaryCost;
    }

    public void setSupportsAltitude(boolean supportsAltitude) {
        mSupportsAltitude = supportsAltitude;
    }

    public void setSupportsSpeed(boolean supportsSpeed) {
        mSupportsSpeed = supportsSpeed;
    }

    public void setSupportsBearing(boolean supportsBearing) {
        mSupportsBearing = supportsBearing;
    }

    public void setPowerRequirement(int powerRequirement) {
        mPowerRequirement = powerRequirement;
    }

    public void setAccuracy(int accuracy) {
        mAccuracy = accuracy;
    }

    /**
     * Returns true if the provider requires access to a
     * data network (e.g., the Internet), false otherwise.
     */
    public boolean requiresNetwork() {
        return mRequiresNetwork;
    }

    /**
     * Returns true if the provider requires access to a
     * satellite-based positioning system (e.g., GPS), false
     * otherwise.
     */
    public boolean requiresSatellite() {
        return mRequiresSatellite;
    }

    /**
     * Returns true if the provider requires access to an appropriate
     * cellular network (e.g., to make use of cell tower IDs), false
     * otherwise.
     */
    public boolean requiresCell() {
        return mRequiresCell;
    }

    /**
     * Returns true if the use of this provider may result in a
     * monetary charge to the user, false if use is free.  It is up to
     * each provider to give accurate information.
     */
    public boolean hasMonetaryCost() {
        return mHasMonetaryCost;
    }

    /**
     * Returns true if the provider is able to provide altitude
     * information, false otherwise.  A provider that reports altitude
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsAltitude() {
        return mSupportsAltitude;
    }

    /**
     * Returns true if the provider is able to provide speed
     * information, false otherwise.  A provider that reports speed
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsSpeed() {
        return mSupportsSpeed;
    }

    /**
     * Returns true if the provider is able to provide bearing
     * information, false otherwise.  A provider that reports bearing
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsBearing() {
        return mSupportsBearing;
    }

    /**
     * Returns the power requirement for this provider.
     *
     * @return the power requirement for this provider, as one of the
     * constants Criteria.POWER_REQUIREMENT_*.
     */
    public int getPowerRequirement() {
        return mPowerRequirement;
    }

    /**
     * Returns a constant describing the horizontal accuracy returned
     * by this provider.
     *
     * @return the horizontal accuracy for this provider, as one of the
     * constants Criteria.ACCURACY_*.
     */
    public int getAccuracy() {
        return mAccuracy;
    }
}

