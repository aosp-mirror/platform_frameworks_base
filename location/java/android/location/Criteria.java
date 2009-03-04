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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class indicating the application criteria for selecting a
 * location provider.  Providers maybe ordered according to accuracy,
 * power usage, ability to report altitude, speed,
 * and bearing, and monetary cost.
 */
public class Criteria implements Parcelable {
    /**
     * A constant indicating that the application does not choose to
     * place requirement on a particular feature.
     */
    public static final int NO_REQUIREMENT = 0;

    /**
     * A constant indicating a low power requirement.
     */
    public static final int POWER_LOW = 1;

    /**
     * A constant indicating a medium power requirement.
     */
    public static final int POWER_MEDIUM = 2;

    /**
     * A constant indicating a high power requirement.
     */
    public static final int POWER_HIGH = 3;

    /**
     * A constant indicating a finer location accuracy requirement
     */
    public static final int ACCURACY_FINE = 1;

    /**
     * A constant indicating an approximate accuracy requirement
     */
    public static final int ACCURACY_COARSE = 2;

    private int mAccuracy              = NO_REQUIREMENT;
    private int mPowerRequirement      = NO_REQUIREMENT;
//    private int mPreferredResponseTime = NO_REQUIREMENT;
    private boolean mAltitudeRequired  = false;
    private boolean mBearingRequired   = false;
    private boolean mSpeedRequired     = false;
    private boolean mCostAllowed       = false;

    /**
     * Constructs a new Criteria object.  The new object will have no
     * requirements on accuracy, power, or response time; will not
     * require altitude, speed, or bearing; and will not allow monetary
     * cost.
     */
    public Criteria() {}

    /**
     * Constructs a new Criteria object that is a copy of the given criteria.
     */
    public Criteria(Criteria criteria) {
        mAccuracy = criteria.mAccuracy;
        mPowerRequirement = criteria.mPowerRequirement;
//        mPreferredResponseTime = criteria.mPreferredResponseTime;
        mAltitudeRequired = criteria.mAltitudeRequired;
        mBearingRequired = criteria.mBearingRequired;
        mSpeedRequired = criteria.mSpeedRequired;
        mCostAllowed = criteria.mCostAllowed;
    }

    /**
     * Indicates the desired accuracy for latitude and longitude. Accuracy
     * may be {@link #ACCURACY_FINE} if desired location
     * is fine, else it can be {@link #ACCURACY_COARSE}.
     * More accurate location usually consumes more power and may take
     * longer.
     *
     * @throws IllegalArgumentException if accuracy is negative
     */
    public void setAccuracy(int accuracy) {
        if (accuracy < NO_REQUIREMENT && accuracy > ACCURACY_COARSE) {
            throw new IllegalArgumentException("accuracy=" + accuracy);
        }
        mAccuracy = accuracy;
    }

    /**
     * Returns a constant indicating desired accuracy of location
     * Accuracy may be {@link #ACCURACY_FINE} if desired location
     * is fine, else it can be {@link #ACCURACY_COARSE}.
     */
    public int getAccuracy() {
        return mAccuracy;
    }

    /**
     * Indicates the desired maximum power level.  The level parameter
     * must be one of NO_REQUIREMENT, POWER_LOW, POWER_MEDIUM, or
     * POWER_HIGH.
     */
    public void setPowerRequirement(int level) {
        if (level < NO_REQUIREMENT || level > POWER_HIGH) {
            throw new IllegalArgumentException("level=" + level);
        }
        mPowerRequirement = level;
    }

    /**
     * Returns a constant indicating the desired power requirement.  The
     * returned
     */
    public int getPowerRequirement() {
        return mPowerRequirement;
    }

//    /**
//     * Indicates the preferred response time of the provider, in milliseconds.
//     */
//    public void setPreferredResponseTime(int time) {
//        mPreferredResponseTime = time;
//    }
//
//    /**
//     * Returns the preferred response time of the provider, in milliseconds.
//     */
//    public int getPreferredResponseTime() {
//        return mPreferredResponseTime;
//    }

    /**
     * Indicates whether the provider is allowed to incur monetary cost.
     */
    public void setCostAllowed(boolean costAllowed) {
        mCostAllowed = costAllowed;
    }

    /**
     * Returns whether the provider is allowed to incur monetary cost.
     */
    public boolean isCostAllowed() {
        return mCostAllowed;
    }

    /**
     * Indicates whether the provider must provide altitude information.
     * Not all fixes are guaranteed to contain such information.
     */
    public void setAltitudeRequired(boolean altitudeRequired) {
        mAltitudeRequired = altitudeRequired;
    }

    /**
     * Returns whether the provider must provide altitude information.
     * Not all fixes are guaranteed to contain such information.
     */
    public boolean isAltitudeRequired() {
        return mAltitudeRequired;
    }

    /**
     * Indicates whether the provider must provide speed information.
     * Not all fixes are guaranteed to contain such information.
     */
    public void setSpeedRequired(boolean speedRequired) {
        mSpeedRequired = speedRequired;
    }

    /**
     * Returns whether the provider must provide speed information.
     * Not all fixes are guaranteed to contain such information.
     */
    public boolean isSpeedRequired() {
        return mSpeedRequired;
    }

    /**
     * Indicates whether the provider must provide bearing information.
     * Not all fixes are guaranteed to contain such information.
     */
    public void setBearingRequired(boolean bearingRequired) {
        mBearingRequired = bearingRequired;
    }

    /**
     * Returns whether the provider must provide bearing information.
     * Not all fixes are guaranteed to contain such information.
     */
    public boolean isBearingRequired() {
        return mBearingRequired;
    }

    public static final Parcelable.Creator<Criteria> CREATOR =
        new Parcelable.Creator<Criteria>() {
        public Criteria createFromParcel(Parcel in) {
            Criteria c = new Criteria();
            c.mAccuracy = in.readInt();
            c.mPowerRequirement = in.readInt();
//            c.mPreferredResponseTime = in.readInt();
            c.mAltitudeRequired = in.readInt() != 0;
            c.mBearingRequired = in.readInt() != 0;
            c.mSpeedRequired = in.readInt() != 0;
            c.mCostAllowed = in.readInt() != 0;
            return c;
        }

        public Criteria[] newArray(int size) {
            return new Criteria[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mAccuracy);
        parcel.writeInt(mPowerRequirement);
//        parcel.writeInt(mPreferredResponseTime);
        parcel.writeInt(mAltitudeRequired ? 1 : 0);
        parcel.writeInt(mBearingRequired ? 1 : 0);
        parcel.writeInt(mSpeedRequired ? 1 : 0);
        parcel.writeInt(mCostAllowed ? 1 : 0);
    }
}
