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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * SatelliteModemEnableRequestAttributes is used to pack info needed by modem to allow carrier to
 * roam to satellite.
 * These attributes will be used by modem to decide how they should act,
 * decide how to attach to the network and whether to enable or disable satellite mode.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public final class SatelliteModemEnableRequestAttributes implements Parcelable {

    /** {@code true} to enable satellite and {@code false} to disable satellite */
    private final boolean mIsEnabled;
    /**
     * {@code true} to enable demo mode and {@code false} to disable. When disabling satellite,
     * {@code mIsDemoMode} is always considered as {@code false} by Telephony.
     */
    private final boolean mIsForDemoMode;
    /**
     * {@code true} means satellite is enabled for emergency mode, {@code false} otherwise. When
     * disabling satellite, {@code isEmergencyMode} is always considered as {@code false} by
     * Telephony.
     */
    private final boolean mIsForEmergencyMode;

    /** The subscription related info */
    @NonNull private final SatelliteSubscriptionInfo mSatelliteSubscriptionInfo;

    /**
     * Constructor for SatelliteModemEnableRequestAttributes objects.
     * @param isEnabled {@code true} to enable satellite and {@code false} to disable satellite
     * @param isForDemoMode {@code true} to enable demo mode and {@code false} to disable.
     * @param isForEmergencyMode {@code true} means satellite is enabled for emergency mode,
     *                        {@code false} otherwise.
     * @param satelliteSubscriptionInfo satellite subscription related info.
     */
    public SatelliteModemEnableRequestAttributes(boolean isEnabled, boolean isForDemoMode,
            boolean isForEmergencyMode,
            @NonNull SatelliteSubscriptionInfo satelliteSubscriptionInfo) {
        mIsEnabled = isEnabled;
        mIsForDemoMode = isForDemoMode;
        mIsForEmergencyMode = isForEmergencyMode;
        mSatelliteSubscriptionInfo = satelliteSubscriptionInfo;
    }

    private SatelliteModemEnableRequestAttributes(Parcel in) {
        mIsEnabled = in.readBoolean();
        mIsForDemoMode = in.readBoolean();
        mIsForEmergencyMode = in.readBoolean();
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
        dest.writeBoolean(mIsForDemoMode);
        dest.writeBoolean(mIsForEmergencyMode);
        mSatelliteSubscriptionInfo.writeToParcel(dest, flags);
    }

    @NonNull
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
                .append(", mIsForDemoMode=").append(mIsForDemoMode)
                .append(", mIsForEmergencyMode=").append(mIsForEmergencyMode)
                .append("mSatelliteSubscriptionInfo=").append(mSatelliteSubscriptionInfo)
                .append("}")
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SatelliteModemEnableRequestAttributes that = (SatelliteModemEnableRequestAttributes) o;
        return mIsEnabled == that.mIsEnabled && mIsForDemoMode == that.mIsForDemoMode
                && mIsForEmergencyMode == that.mIsForEmergencyMode
                && mSatelliteSubscriptionInfo.equals(that.mSatelliteSubscriptionInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsEnabled, mIsForDemoMode, mIsForEmergencyMode,
                mSatelliteSubscriptionInfo);
    }


    /**
     * Get whether satellite modem needs to be enabled or disabled.
     * @return {@code true} if the request is to enable satellite, else {@code false} to disable
     * satellite.
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Get whether satellite modem is enabled for demo mode.
     * @return {@code true} if the request is to enable demo mode, else {@code false}.
     */
    public boolean isForDemoMode() {
        return mIsForDemoMode;
    }

    /**
     * Get whether satellite modem is enabled for emergency mode.
     * @return {@code true} if the request is to enable satellite for emergency mode,
     * else {@code false}.
     */
    public boolean isForEmergencyMode() {
        return mIsForEmergencyMode;
    }


    /**
     * Return subscription info related to satellite.
     */
    @NonNull
    public SatelliteSubscriptionInfo getSatelliteSubscriptionInfo() {
        return mSatelliteSubscriptionInfo;
    }
}
