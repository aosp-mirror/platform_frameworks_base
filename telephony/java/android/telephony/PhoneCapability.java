/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phone capability which describes the data connection capability of modem.
 * It's used to evaluate possible phone config change, for example from single
 * SIM device to multi-SIM device.
 * @hide
 */
@SystemApi
public final class PhoneCapability implements Parcelable {
    // Hardcoded default DSDS capability.
    /** @hide */
    public static final PhoneCapability DEFAULT_DSDS_CAPABILITY;
    // Hardcoded default Single SIM single standby capability.
    /** @hide */
    public static final PhoneCapability DEFAULT_SSSS_CAPABILITY;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "DEVICE_NR_CAPABILITY_" }, value = {
            DEVICE_NR_CAPABILITY_NONE,
            DEVICE_NR_CAPABILITY_NSA,
            DEVICE_NR_CAPABILITY_SA,
    })
    public @interface DeviceNrCapability {}

    /**
     * Indicates DEVICE_NR_CAPABILITY_NONE determine that the device does not enable 5G NR.
     * @hide
     */
    @SystemApi
    public static final int DEVICE_NR_CAPABILITY_NONE = 0;

    /**
     * Indicates DEVICE_NR_CAPABILITY_NSA determine that the device enable the non-standalone
     * (NSA) mode of 5G NR.
     * @hide
     */
    @SystemApi
    public static final int DEVICE_NR_CAPABILITY_NSA = 1 << 0;

    /**
     * Indicates DEVICE_NR_CAPABILITY_SA determine that the device enable the standalone (SA)
     * mode of 5G NR.
     * @hide
     */
    @SystemApi
    public static final int DEVICE_NR_CAPABILITY_SA = 1 << 1;

    static {
        ModemInfo modemInfo1 = new ModemInfo(0, 0, true, true);
        ModemInfo modemInfo2 = new ModemInfo(1, 0, true, true);

        List<ModemInfo> logicalModemList = new ArrayList<>();
        logicalModemList.add(modemInfo1);
        logicalModemList.add(modemInfo2);
        DEFAULT_DSDS_CAPABILITY = new PhoneCapability(1, 1, logicalModemList, false,
                DEVICE_NR_CAPABILITY_NONE);

        logicalModemList = new ArrayList<>();
        logicalModemList.add(modemInfo1);
        DEFAULT_SSSS_CAPABILITY = new PhoneCapability(1, 1, logicalModemList, false,
                DEVICE_NR_CAPABILITY_NONE);
    }

    /**
     * MaxActivePsVoice defines the maximum number of active voice calls.  For a dual sim dual
     * standby (DSDS) modem it would be one, but for a dual sim dual active modem it would be 2.
     *
     * @hide
     */
    private final int mMaxActivePsVoice;

    /**
     * MaxActiveInternetData defines how many logical modems can have
     * PS attached simultaneously. For example, for L+L modem it
     * should be 2.
     *
     * @hide
     */
    private final int mMaxActiveInternetData;

    /**
     * Whether modem supports both internet PDN up so
     * that we can do ping test before tearing down the
     * other one.
     *
     * @hide
     */
    private final boolean mNetworkValidationBeforeSwitchSupported;

    /** @hide */
    private final List<ModemInfo> mLogicalModemList;

    /**
     * List of logical modem information.
     *
     * @hide
     */
    private final int mDeviceNrCapability;

    /** @hide */
    public PhoneCapability(int maxActivePsVoice, int maxActiveInternetData,
            List<ModemInfo> logicalModemList, boolean networkValidationBeforeSwitchSupported,
            int deviceNrCapability) {
        this.mMaxActivePsVoice = maxActivePsVoice;
        this.mMaxActiveInternetData = maxActiveInternetData;
        // Make sure it's not null.
        this.mLogicalModemList = logicalModemList == null ? new ArrayList<>() : logicalModemList;
        this.mNetworkValidationBeforeSwitchSupported = networkValidationBeforeSwitchSupported;
        this.mDeviceNrCapability = deviceNrCapability;
    }

    @Override
    public String toString() {
        return "mMaxActivePsVoice=" + mMaxActivePsVoice
                + " mMaxActiveInternetData=" + mMaxActiveInternetData
                + " mNetworkValidationBeforeSwitchSupported="
                + mNetworkValidationBeforeSwitchSupported
                + " mDeviceNrCapability " + mDeviceNrCapability;
    }

    private PhoneCapability(Parcel in) {
        mMaxActivePsVoice = in.readInt();
        mMaxActiveInternetData = in.readInt();
        mNetworkValidationBeforeSwitchSupported = in.readBoolean();
        mLogicalModemList = new ArrayList<>();
        in.readList(mLogicalModemList, ModemInfo.class.getClassLoader());
        mDeviceNrCapability = in.readInt();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMaxActivePsVoice, mMaxActiveInternetData, mLogicalModemList,
                mNetworkValidationBeforeSwitchSupported, mDeviceNrCapability);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof PhoneCapability) || hashCode() != o.hashCode()) {
            return false;
        }

        if (this == o) {
            return true;
        }

        PhoneCapability s = (PhoneCapability) o;

        return (mMaxActivePsVoice == s.mMaxActivePsVoice
                && mMaxActiveInternetData == s.mMaxActiveInternetData
                && mNetworkValidationBeforeSwitchSupported
                == s.mNetworkValidationBeforeSwitchSupported
                && mLogicalModemList.equals(s.mLogicalModemList)
                && mDeviceNrCapability == s.mDeviceNrCapability);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(@NonNull Parcel dest, @Parcelable.WriteFlags int flags) {
        dest.writeInt(mMaxActivePsVoice);
        dest.writeInt(mMaxActiveInternetData);
        dest.writeBoolean(mNetworkValidationBeforeSwitchSupported);
        dest.writeList(mLogicalModemList);
        dest.writeInt(mDeviceNrCapability);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PhoneCapability> CREATOR =
            new Parcelable.Creator() {
        public PhoneCapability createFromParcel(Parcel in) {
            return new PhoneCapability(in);
        }

        public PhoneCapability[] newArray(int size) {
            return new PhoneCapability[size];
        }
    };

    /**
     * @return the maximum number of active packet-switched calls.  For a dual
     * sim dual standby (DSDS) modem it would be one, but for a dual sim dual active modem it
     * would be 2.
     * @hide
     */
    @SystemApi
    public @IntRange(from = 1) int getMaxActivePacketSwitchedVoiceCalls() {
        return mMaxActivePsVoice;
    }

    /**
     * @return MaxActiveInternetData defines how many logical modems can have PS attached
     * simultaneously.
     * For example, for L+L modem it should be 2.
     * @hide
     */
    @SystemApi
    public @IntRange(from = 1) int getMaxActiveInternetData() {
        return mMaxActiveInternetData;
    }

    /**
     * @return Check whether the Citizens Broadband Radio Service(CBRS) network validation before
     * CBRS switch is supported or not.
     *
     * @hide
     */
    public boolean isNetworkValidationBeforeSwitchSupported() {
        return mNetworkValidationBeforeSwitchSupported;
    }

    /**
     * @return The list of logical modem information.
     * @hide
     */
    public List<ModemInfo> getLogicalModemList() {
        return mLogicalModemList;
    }

    /**
     * Return the device's NR capability.
     *
     * @return {@link DeviceNrCapability} the device's NR capability.
     * @hide
     */
    @SystemApi
    public @DeviceNrCapability int getDeviceNrCapabilityBitmask() {
        return mDeviceNrCapability;
    }
}
