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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class indicating the application criteria for selecting a
 * location provider. Providers may be ordered according to accuracy,
 * power usage, ability to report altitude, speed, bearing, and monetary
 * cost.
 */
public class Criteria implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NO_REQUIREMENT, POWER_LOW, POWER_MEDIUM, POWER_HIGH})
    public @interface PowerRequirement {
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NO_REQUIREMENT, ACCURACY_LOW, ACCURACY_MEDIUM, ACCURACY_HIGH})
    public @interface AccuracyRequirement {
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NO_REQUIREMENT, ACCURACY_FINE, ACCURACY_COARSE})
    public @interface LocationAccuracyRequirement {
    }

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

    private int mHorizontalAccuracy = NO_REQUIREMENT;
    private int mVerticalAccuracy = NO_REQUIREMENT;
    private int mSpeedAccuracy = NO_REQUIREMENT;
    private int mBearingAccuracy = NO_REQUIREMENT;
    private int mPowerRequirement = NO_REQUIREMENT;
    private boolean mAltitudeRequired = false;
    private boolean mBearingRequired = false;
    private boolean mSpeedRequired = false;
    private boolean mCostAllowed = false;

    /**
     * Constructs a new Criteria object.  The new object will have no
     * requirements on accuracy, power, or response time; will not
     * require altitude, speed, or bearing; and will not allow monetary
     * cost.
     */
    public Criteria() {
    }

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
     * Indicates the desired horizontal accuracy (latitude and longitude). Accuracy may be
     * {@link #ACCURACY_LOW}, {@link #ACCURACY_MEDIUM}, {@link #ACCURACY_HIGH} or
     * {@link #NO_REQUIREMENT}. More accurate location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setHorizontalAccuracy(@AccuracyRequirement int accuracy) {
        mHorizontalAccuracy = Preconditions.checkArgumentInRange(accuracy, NO_REQUIREMENT,
                ACCURACY_HIGH, "accuracy");
    }

    /**
     * Returns a constant indicating the desired horizontal accuracy (latitude and longitude).
     *
     * @see #setHorizontalAccuracy(int)
     */
    @AccuracyRequirement
    public int getHorizontalAccuracy() {
        return mHorizontalAccuracy;
    }

    /**
     * Indicates the desired vertical accuracy (altitude). Accuracy may be {@link #ACCURACY_LOW},
     * {@link #ACCURACY_MEDIUM}, {@link #ACCURACY_HIGH} or {@link #NO_REQUIREMENT}. More accurate
     * location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setVerticalAccuracy(@AccuracyRequirement int accuracy) {
        mVerticalAccuracy = Preconditions.checkArgumentInRange(accuracy, NO_REQUIREMENT,
                ACCURACY_HIGH, "accuracy");
    }

    /**
     * Returns a constant indicating the desired vertical accuracy (altitude).
     *
     * @see #setVerticalAccuracy(int)
     */
    @AccuracyRequirement
    public int getVerticalAccuracy() {
        return mVerticalAccuracy;
    }

    /**
     * Indicates the desired speed accuracy. Accuracy may be {@link #ACCURACY_LOW},
     * {@link #ACCURACY_MEDIUM}, {@link #ACCURACY_HIGH}, or {@link #NO_REQUIREMENT}. More accurate
     * location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setSpeedAccuracy(@AccuracyRequirement int accuracy) {
        mSpeedAccuracy = Preconditions.checkArgumentInRange(accuracy, NO_REQUIREMENT, ACCURACY_HIGH,
                "accuracy");
    }

    /**
     * Returns a constant indicating the desired speed accuracy.
     *
     * @see #setSpeedAccuracy(int)
     */
    @AccuracyRequirement
    public int getSpeedAccuracy() {
        return mSpeedAccuracy;
    }

    /**
     * Indicates the desired bearing accuracy. Accuracy may be {@link #ACCURACY_LOW},
     * {@link #ACCURACY_MEDIUM}, {@link #ACCURACY_HIGH}, or {@link #NO_REQUIREMENT}. More accurate
     * location may consume more power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setBearingAccuracy(@AccuracyRequirement int accuracy) {
        mBearingAccuracy = Preconditions.checkArgumentInRange(accuracy, NO_REQUIREMENT,
                ACCURACY_HIGH, "accuracy");
    }

    /**
     * Returns a constant indicating the desired bearing accuracy.
     *
     * @see #setBearingAccuracy(int)
     */
    @AccuracyRequirement
    public int getBearingAccuracy() {
        return mBearingAccuracy;
    }

    /**
     * Indicates the desired accuracy for latitude and longitude. Accuracy may be
     * {@link #ACCURACY_FINE} or {@link #ACCURACY_COARSE}. More accurate location may consume more
     * power and may take longer.
     *
     * @throws IllegalArgumentException if accuracy is not one of the supported constants
     */
    public void setAccuracy(@LocationAccuracyRequirement int accuracy) {
        Preconditions.checkArgumentInRange(accuracy, NO_REQUIREMENT, ACCURACY_COARSE, "accuracy");
        switch (accuracy) {
            case NO_REQUIREMENT:
                setHorizontalAccuracy(NO_REQUIREMENT);
                break;
            case ACCURACY_FINE:
                setHorizontalAccuracy(ACCURACY_HIGH);
                break;
            case ACCURACY_COARSE:
                setHorizontalAccuracy(ACCURACY_LOW);
                break;
        }
    }

    /**
     * Returns a constant indicating desired accuracy of location.
     *
     * @see #setAccuracy(int)
     */
    @LocationAccuracyRequirement
    public int getAccuracy() {
        if (mHorizontalAccuracy >= ACCURACY_HIGH) {
            return ACCURACY_FINE;
        } else {
            return ACCURACY_COARSE;
        }
    }

    /**
     * Indicates the desired maximum power requirement. The power requirement parameter may be
     * {@link #NO_REQUIREMENT}, {@link #POWER_LOW}, {@link #POWER_MEDIUM}, or {@link #POWER_HIGH}.
     */
    public void setPowerRequirement(@PowerRequirement int powerRequirement) {
        mPowerRequirement = Preconditions.checkArgumentInRange(powerRequirement, NO_REQUIREMENT,
                POWER_HIGH, "powerRequirement");
    }

    /**
     * Returns a constant indicating the desired maximum power requirement.
     *
     * @see #setPowerRequirement(int)
     */
    @PowerRequirement
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
     * Indicates whether the provider must provide altitude information. Not all fixes are
     * guaranteed to contain such information.
     */
    public void setAltitudeRequired(boolean altitudeRequired) {
        mAltitudeRequired = altitudeRequired;
    }

    /**
     * Returns whether the provider must provide altitude information.
     *
     * @see #setAltitudeRequired(boolean)
     */
    public boolean isAltitudeRequired() {
        return mAltitudeRequired;
    }

    /**
     * Indicates whether the provider must provide speed information. Not all fixes are guaranteed
     * to contain such information.
     */
    public void setSpeedRequired(boolean speedRequired) {
        mSpeedRequired = speedRequired;
    }

    /**
     * Returns whether the provider must provide speed information.
     *
     * @see #setSpeedRequired(boolean)
     */
    public boolean isSpeedRequired() {
        return mSpeedRequired;
    }

    /**
     * Indicates whether the provider must provide bearing information. Not all fixes are guaranteed
     * to contain such information.
     */
    public void setBearingRequired(boolean bearingRequired) {
        mBearingRequired = bearingRequired;
    }

    /**
     * Returns whether the provider must provide bearing information.
     *
     * @see #setBearingRequired(boolean)
     */
    public boolean isBearingRequired() {
        return mBearingRequired;
    }

    @NonNull
    public static final Parcelable.Creator<Criteria> CREATOR =
            new Parcelable.Creator<Criteria>() {
                @Override
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

                @Override
                public Criteria[] newArray(int size) {
                    return new Criteria[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
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

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Criteria[");
        s.append("power=").append(requirementToString(mPowerRequirement)).append(", ");
        s.append("accuracy=").append(requirementToString(mHorizontalAccuracy));
        if (mVerticalAccuracy != NO_REQUIREMENT) {
            s.append(", verticalAccuracy=").append(requirementToString(mVerticalAccuracy));
        }
        if (mSpeedAccuracy != NO_REQUIREMENT) {
            s.append(", speedAccuracy=").append(requirementToString(mSpeedAccuracy));
        }
        if (mBearingAccuracy != NO_REQUIREMENT) {
            s.append(", bearingAccuracy=").append(requirementToString(mBearingAccuracy));
        }
        if (mAltitudeRequired || mBearingRequired || mSpeedRequired) {
            s.append(", required=[");
            if (mAltitudeRequired) {
                s.append("altitude, ");
            }
            if (mBearingRequired) {
                s.append("bearing, ");
            }
            if (mSpeedRequired) {
                s.append("speed, ");
            }
            s.setLength(s.length() - 2);
            s.append("]");
        }
        if (mCostAllowed) {
            s.append(", costAllowed");
        }
        s.append(']');
        return s.toString();
    }

    private static String requirementToString(int power) {
        switch (power) {
            case NO_REQUIREMENT:
                return "None";
            //case ACCURACY_LOW:
            case POWER_LOW:
                return "Low";
            //case ACCURACY_MEDIUM:
            case POWER_MEDIUM:
                return "Medium";
            //case ACCURACY_HIGH:
            case POWER_HIGH:
                return "High";
            default:
                return "???";
        }
    }
}
