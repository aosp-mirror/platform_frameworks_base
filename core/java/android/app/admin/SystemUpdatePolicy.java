/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app.admin;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class that represents a local system update policy set by the device owner.
 *
 * @see DevicePolicyManager#setSystemUpdatePolicy
 * @see DevicePolicyManager#getSystemUpdatePolicy
 */
public class SystemUpdatePolicy implements Parcelable {

    /** @hide */
    @IntDef({
        TYPE_INSTALL_AUTOMATIC,
        TYPE_INSTALL_WINDOWED,
        TYPE_POSTPONE})
    @Retention(RetentionPolicy.SOURCE)
    @interface SystemUpdatePolicyType {}

    /**
     * Unknown policy type, used only internally.
     */
    private static final int TYPE_UNKNOWN = -1;
    /**
     * Install system update automatically as soon as one is available.
     */
    public static final int TYPE_INSTALL_AUTOMATIC = 1;

    /**
     * Install system update automatically within a daily maintenance window, for a maximum of 30
     * days. After the expiration the policy will no longer be effective and the system should
     * revert back to its normal behavior as if no policy were set. The only exception is
     * {@link #TYPE_INSTALL_AUTOMATIC} which should still take effect to install system update
     * immediately.
     */
    public static final int TYPE_INSTALL_WINDOWED = 2;

    /**
     * Incoming system update will be blocked for a maximum of 30 days, after which the system
     * should revert back to its normal behavior as if no policy were set. The only exception is
     * {@link #TYPE_INSTALL_AUTOMATIC} which should still take effect to install system update
     * immediately.
     */
    public static final int TYPE_POSTPONE = 3;

    private static final String KEY_POLICY_TYPE = "policy_type";
    private static final String KEY_INSTALL_WINDOW_START = "install_window_start";
    private static final String KEY_INSTALL_WINDOW_END = "install_window_end";
    /**
     * The upper boundary of the daily maintenance window: 24 * 60 minutes.
     */
    private static final int WINDOW_BOUNDARY = 24 * 60;

    @SystemUpdatePolicyType
    private int mPolicyType;

    private int mMaintenanceWindowStart;
    private int mMaintenanceWindowEnd;


    private SystemUpdatePolicy() {
        mPolicyType = TYPE_UNKNOWN;
    }

    /**
     * Create a policy object and set it to install update automatically as soon as one is
     * available.
     *
     * @see #TYPE_INSTALL_AUTOMATIC
     */
    public static SystemUpdatePolicy createAutomaticInstallPolicy() {
        SystemUpdatePolicy policy = new SystemUpdatePolicy();
        policy.mPolicyType = TYPE_INSTALL_AUTOMATIC;
        return policy;
    }

    /**
     * Create a policy object and set it to: new system update will only be installed automatically
     * when the system clock is inside a daily maintenance window. If the start and end times are
     * the same, the window is considered to include the WHOLE 24 hours, that is, updates can
     * install at any time. If the given window in invalid, a {@link IllegalArgumentException} will
     * be thrown. If start time is later than end time, the window is considered spanning midnight,
     * i.e. end time donates a time on the next day. The maintenance window will last for 30 days,
     * after which the system should revert back to its normal behavior as if no policy were set.
     *
     * @param startTime the start of the maintenance window, measured as the number of minutes from
     *            midnight in the device's local time. Must be in the range of [0, 1440).
     * @param endTime the end of the maintenance window, measured as the number of minutes from
     *            midnight in the device's local time. Must be in the range of [0, 1440).
     * @see #TYPE_INSTALL_WINDOWED
     */
    public static SystemUpdatePolicy createWindowedInstallPolicy(int startTime, int endTime) {
        if (startTime < 0 || startTime >= WINDOW_BOUNDARY
                || endTime < 0 || endTime >= WINDOW_BOUNDARY) {
            throw new IllegalArgumentException("startTime and endTime must be inside [0, 1440)");
        }
        SystemUpdatePolicy policy = new SystemUpdatePolicy();
        policy.mPolicyType = TYPE_INSTALL_WINDOWED;
        policy.mMaintenanceWindowStart = startTime;
        policy.mMaintenanceWindowEnd = endTime;
        return policy;
    }

    /**
     * Create a policy object and set it to block installation for a maximum period of 30 days.
     * After expiration the system should revert back to its normal behavior as if no policy were
     * set.
     *
     * @see #TYPE_POSTPONE
     */
    public static SystemUpdatePolicy createPostponeInstallPolicy() {
        SystemUpdatePolicy policy = new SystemUpdatePolicy();
        policy.mPolicyType = TYPE_POSTPONE;
        return policy;
    }

    /**
     * Returns the type of system update policy.
     *
     * @return an integer, either one of {@link #TYPE_INSTALL_AUTOMATIC},
     * {@link #TYPE_INSTALL_WINDOWED} and {@link #TYPE_POSTPONE}, or -1 if no policy has been set.
     */
    @SystemUpdatePolicyType
    public int getPolicyType() {
        return mPolicyType;
    }

    /**
     * Get the start of the maintenance window.
     *
     * @return the start of the maintenance window measured as the number of minutes from midnight,
     * or -1 if the policy does not have a maintenance window.
     */
    public int getInstallWindowStart() {
        if (mPolicyType == TYPE_INSTALL_WINDOWED) {
            return mMaintenanceWindowStart;
        } else {
            return -1;
        }
    }

    /**
     * Get the end of the maintenance window.
     *
     * @return the end of the maintenance window measured as the number of minutes from midnight,
     * or -1 if the policy does not have a maintenance window.
     */
    public int getInstallWindowEnd() {
        if (mPolicyType == TYPE_INSTALL_WINDOWED) {
            return mMaintenanceWindowEnd;
        } else {
            return -1;
        }
    }

    /**
     * Return if this object represents a valid policy.
     * @hide
     */
    public boolean isValid() {
        if (mPolicyType == TYPE_INSTALL_AUTOMATIC || mPolicyType == TYPE_POSTPONE) {
            return true;
        } else if (mPolicyType == TYPE_INSTALL_WINDOWED) {
            return mMaintenanceWindowStart >= 0 && mMaintenanceWindowStart < WINDOW_BOUNDARY
                    && mMaintenanceWindowEnd >= 0 && mMaintenanceWindowEnd < WINDOW_BOUNDARY;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return String.format("SystemUpdatePolicy (type: %d, windowStart: %d, windowEnd: %d)",
                mPolicyType, mMaintenanceWindowStart, mMaintenanceWindowEnd);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPolicyType);
        dest.writeInt(mMaintenanceWindowStart);
        dest.writeInt(mMaintenanceWindowEnd);
    }

    public static final Parcelable.Creator<SystemUpdatePolicy> CREATOR =
            new Parcelable.Creator<SystemUpdatePolicy>() {

                @Override
                public SystemUpdatePolicy createFromParcel(Parcel source) {
                    SystemUpdatePolicy policy = new SystemUpdatePolicy();
                    policy.mPolicyType = source.readInt();
                    policy.mMaintenanceWindowStart = source.readInt();
                    policy.mMaintenanceWindowEnd = source.readInt();
                    return policy;
                }

                @Override
                public SystemUpdatePolicy[] newArray(int size) {
                    return new SystemUpdatePolicy[size];
                }
    };


    /**
     * @hide
     */
    public static SystemUpdatePolicy restoreFromXml(XmlPullParser parser) {
        try {
            SystemUpdatePolicy policy = new SystemUpdatePolicy();
            String value = parser.getAttributeValue(null, KEY_POLICY_TYPE);
            if (value != null) {
                policy.mPolicyType = Integer.parseInt(value);

                value = parser.getAttributeValue(null, KEY_INSTALL_WINDOW_START);
                if (value != null) {
                    policy.mMaintenanceWindowStart = Integer.parseInt(value);
                }
                value = parser.getAttributeValue(null, KEY_INSTALL_WINDOW_END);
                if (value != null) {
                    policy.mMaintenanceWindowEnd = Integer.parseInt(value);
                }
                return policy;
            }
        } catch (NumberFormatException e) {
            // Fail through
        }
        return null;
    }

    /**
     * @hide
     */
    public void saveToXml(XmlSerializer out) throws IOException {
        out.attribute(null, KEY_POLICY_TYPE, Integer.toString(mPolicyType));
        out.attribute(null, KEY_INSTALL_WINDOW_START, Integer.toString(mMaintenanceWindowStart));
        out.attribute(null, KEY_INSTALL_WINDOW_END, Integer.toString(mMaintenanceWindowEnd));
    }
}

