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

    /**
     * A constant indicating a low location accuracy requirement
     * - may be used for horizontal, altitude, speed or bearing accuracy.
     * For horizontal and vertical position this corresponds roughly to
     * an accuracy of greater than 500 meters.
     */
    public static final int ACCURACY_LOW = 1;

    /**
     * A constant indicating a medium accuracy requirement
     * - currently used only for horizontal accuracy.
     * For horizontal position this corresponds roughly to to an accuracy
     * of between 100 and 500 meters.
     */
    public static final int ACCURACY_MEDIUM = 2;

    /**
     * a constant indicating a high accuracy requirement
     * - may be used for horizontal, altitude, speed or bearing accuracy.
     * For horizontal and vertical position this corresponds roughly to
     * an accuracy of less than 100 meters.
     */
    public static final int ACCURACY_HIGH = 3;

    private int mHorizontalAccuracy    = NO_REQUIREMENT;
    private int mVerticalAccuracy      = NO_REQUIREMENT;
    private int mSpeedAccuracy         = NO_REQUIREMENT;
    private int mBearingAccuracy       = NO_REQUIREMENT;
    private int mPowerRequirement      = NO_REQUIREMENT;
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
        mHorizontalAccuracy = criteria.mHorizontalAccuracy;
        mVerticalAccuracy = criteria.mVerticalAccuracy;
        mSpeedAccuracy = criteria.mSpeedAccuracy;
        mBearingAccuracy = criteria.mBearingAccuracy;
        mPowerRequirement = criteria.mPowerRequirement;
        mAltitudeRequired = criteria.mAltitudeRequired;
        mBearingRequired = criteria.mBearingRequired;
        mSpeedRequired = criteria.mSpeedRequired;
        mCostAllowed = criteria.mCostAllowed;
    }

    /**
     * Indicates the desired horizontal accuracy (latitude and longitude).
     * Accuracy may be {@link #ACCURACY_LOW}, {@link #ACCURACY_MEDIUM},
     * {@link #ACCURACY_HIGH} or {@link #NO_REQUIREMENT}.
     * More accurate location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setHorizontalAccuracy(int accuracy) {
        if (accuracy < NO_REQUIREMENT || accuracy > ACCURACY_HIGH) {
            throw new IllegalArgumentException("accuracy=" + accuracy);
        }
        mHorizontalAccuracy = accuracy;
    }

    /**
     * Returns a constant indicating the desired horizontal accuracy (latitude and longitude).
     * Accuracy may be {@link #ACCURACY_LOW}, {@link #ACCURACY_MEDIUM},
     * {@link #ACCURACY_HIGH} or {@link #NO_REQUIREMENT}.
     */
    public int getHorizontalAccuracy() {
        return mHorizontalAccuracy;
    }

    /**
     * Indicates the desired vertical accuracy (altitude).
     * Accuracy may be {@link #ACCURACY_LOW}, {@link #ACCURACY_MEDIUM},
     * {@link #ACCURACY_HIGH} or {@link #NO_REQUIREMENT}.
     * More accurate location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setVerticalAccuracy(int accuracy) {
        if (accuracy < NO_REQUIREMENT || accuracy > ACCURACY_HIGH) {
            throw new IllegalArgumentException("accuracy=" + accuracy);
        }
        mVerticalAccuracy = accuracy;
    }

    /**
     * Returns a constant indicating the desired vertical accuracy (altitude).
     * Accuracy may be {@link #ACCURACY_LOW}, {@link #ACCURACY_HIGH},
     * or {@link #NO_REQUIREMENT}.
     */
    public int getVerticalAccuracy() {
        return mVerticalAccuracy;
    }

    /**
     * Indicates the desired speed accuracy.
     * Accuracy may be {@link #ACCURACY_LOW}, {@link #ACCURACY_HIGH},
     * or {@link #NO_REQUIREMENT}.
     * More accurate location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setSpeedAccuracy(int accuracy) {
        if (accuracy < NO_REQUIREMENT || accuracy > ACCURACY_HIGH) {
            throw new IllegalArgumentException("accuracy=" + accuracy);
        }
        mSpeedAccuracy = accuracy;
    }

    /**
     * Returns a constant indicating the desired speed accuracy
     * Accuracy may be {@link #ACCURACY_LOW}, {@link #ACCURACY_HIGH},
     * or {@link #NO_REQUIREMENT}.
     */
    public int getSpeedAccuracy() {
        return mSpeedAccuracy;
    }

    /**
     * Indicates the desired bearing accuracy.
     * Accuracy may be {@link #ACCURACY_LOW}, {@link #ACCURACY_HIGH},
     * or {@link #NO_REQUIREMENT}.
     * More accurate location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setBearingAccuracy(int accuracy) {
        if (accuracy < NO_REQUIREMENT || accuracy > ACCURACY_HIGH) {
            throw new IllegalArgumentException("accuracy=" + accuracy);
        }
        mBearingAccuracy = accuracy;
    }

    /**
     * Returns a constant indicating the desired bearing accuracy.
     * Accuracy may be {@link #ACCURACY_LOW}, {@link #ACCURACY_HIGH},
     * or {@link #NO_REQUIREMENT}.
     */
    public int getBearingAccuracy() {
        return mBearingAccuracy;
    }

    /**
     * Indicates the desired accuracy for latitude and longitude. Accuracy
     * may be {@link #ACCURACY_FINE} if desired location
     * is fine, else it can be {@link #ACCURACY_COARSE}.
     * More accurate location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setAccuracy(int accuracy) {
        if (accuracy < NO_REQUIREMENT || accuracy > ACCURACY_COARSE) {
            throw new IllegalArgumentException("accuracy=" + accuracy);
        }
        if (accuracy == ACCURACY_FINE) {
            mHorizontalAccuracy = ACCURACY_HIGH;
        } else {
            mHorizontalAccuracy = ACCURACY_LOW;
        }
    }

    /**
     * Returns a constant indicating desired accuracy of location
     * Accuracy may be {@link #ACCURACY_FINE} if desired location
     * is fine, else it can be {@link #ACCURACY_COARSE}.
     */
    public int getAccuracy() {
        if (mHorizontalAccuracy >= ACCURACY_HIGH) {
            return ACCURACY_FINE;
        } else {
            return ACCURACY_COARSE;
        }
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
            c.mHorizontalAccuracy = in.readInt();
            c.mVerticalAccuracy = in.readInt();
            c.mSpeedAccuracy = in.readInt();
            c.mBearingAccuracy = in.readInt();
            c.mPowerRequirement = in.readInt();
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
        parcel.writeInt(mHorizontalAccuracy);
        parcel.writeInt(mVerticalAccuracy);
        parcel.writeInt(mSpeedAccuracy);
        parcel.writeInt(mBearingAccuracy);
        parcel.writeInt(mPowerRequirement);
        parcel.writeInt(mAltitudeRequired ? 1 : 0);
        parcel.writeInt(mBearingRequired ? 1 : 0);
        parcel.writeInt(mSpeedRequired ? 1 : 0);
        parcel.writeInt(mCostAllowed ? 1 : 0);
    }
}
