/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.telephony.satellite;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * SatelliteModemEnableRequestAttributes is used to pack info needed by modem to allow carrier to
 * roam to satellite.
 *
 * @hide
 */
public final class SatelliteModemEnableRequestAttributes implements Parcelable {

    /** {@code true} to enable satellite and {@code false} to disable satellite */
    private final boolean mIsEnabled;
    /**
     * {@code true} to enable demo mode and {@code false} to disable. When disabling satellite,
     * {@code mIsDemoMode} is always considered as {@code false} by Telephony.
     */
    private final boolean mIsDemoMode;
    /**
     * {@code true} means satellite is enabled for emergency mode, {@code false} otherwise. When
     * disabling satellite, {@code isEmergencyMode} is always considered as {@code false} by
     * Telephony.
     */
    private final boolean mIsEmergencyMode;

    /** The subscription related info */
    @NonNull private final SatelliteSubscriptionInfo mSatelliteSubscriptionInfo;

    public SatelliteModemEnableRequestAttributes(boolean isEnabled, boolean isDemoMode,
            boolean isEmergencyMode, @NonNull SatelliteSubscriptionInfo satelliteSubscriptionInfo) {
        mIsEnabled = isEnabled;
        mIsDemoMode = isDemoMode;
        mIsEmergencyMode = isEmergencyMode;
        mSatelliteSubscriptionInfo = satelliteSubscriptionInfo;
    }

    private SatelliteModemEnableRequestAttributes(Parcel in) {
        mIsEnabled = in.readBoolean();
        mIsDemoMode = in.readBoolean();
        mIsEmergencyMode = in.readBoolean();
        mSatelliteSubscriptionInfo = in.readParcelable(
                SatelliteSubscriptionInfo.class.getClassLoader(), SatelliteSubscriptionInfo.class);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsEnabled);
        dest.writeBoolean(mIsDemoMode);
        dest.writeBoolean(mIsEmergencyMode);
        mSatelliteSubscriptionInfo.writeToParcel(dest, flags);
    }

    public static final Creator<SatelliteModemEnableRequestAttributes> CREATOR = new Creator<>() {
        @Override
        public SatelliteModemEnableRequestAttributes createFromParcel(Parcel in) {
            return new SatelliteModemEnableRequestAttributes(in);
        }

        @Override
        public SatelliteModemEnableRequestAttributes[] newArray(int size) {
            return new SatelliteModemEnableRequestAttributes[size];
        }
    };

    @Override
    public String toString() {
        return (new StringBuilder()).append("SatelliteModemEnableRequestAttributes{")
                .append(", mIsEnabled=").append(mIsEnabled)
                .append(", mIsDemoMode=").append(mIsDemoMode)
                .append(", mIsDemoMode=").append(mIsDemoMode)
                .append("mSatelliteSubscriptionInfo=").append(mSatelliteSubscriptionInfo)
                .append("}")
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SatelliteModemEnableRequestAttributes that = (SatelliteModemEnableRequestAttributes) o;
        return mIsEnabled == that.mIsEnabled && mIsDemoMode == that.mIsDemoMode
                && mIsEmergencyMode == that.mIsEmergencyMode && mSatelliteSubscriptionInfo.equals(
                that.mSatelliteSubscriptionInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsEnabled, mIsDemoMode, mIsEmergencyMode, mSatelliteSubscriptionInfo);
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public boolean isDemoMode() {
        return mIsDemoMode;
    }

    public boolean isEmergencyMode() {
        return mIsEmergencyMode;
    }

    @NonNull public SatelliteSubscriptionInfo getSatelliteSubscriptionInfo() {
        return mSatelliteSubscriptionInfo;
    }
}
