/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.hdmi;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class to encapsulate device information for HDMI-CEC. This container
 * include basic information such as logical address, physical address and
 * device type, and additional information like vendor id and osd name.
 *
 * @hide
 */
@SystemApi
public final class HdmiCecDeviceInfo implements Parcelable {
    // Logical address, phsical address, device type, vendor id and display name
    // are immutable value.
    private final int mLogicalAddress;
    private final int mPhysicalAddress;
    private final int mDeviceType;
    private final int mVendorId;
    private final String mDisplayName;


    /**
     * A helper class to deserialize {@link HdmiCecDeviceInfo} for a parcel.
     */
    public static final Parcelable.Creator<HdmiCecDeviceInfo> CREATOR =
            new Parcelable.Creator<HdmiCecDeviceInfo>() {
                @Override
                public HdmiCecDeviceInfo createFromParcel(Parcel source) {
                    int logicalAddress = source.readInt();
                    int physicalAddress = source.readInt();
                    int deviceType = source.readInt();
                    int vendorId = source.readInt();
                    String displayName = source.readString();
                    return new HdmiCecDeviceInfo(logicalAddress, physicalAddress, deviceType,
                            vendorId, displayName);
                }

                @Override
                public HdmiCecDeviceInfo[] newArray(int size) {
                    return new HdmiCecDeviceInfo[size];
                }
            };

    /**
     * Constructor.
     *
     * @param logicalAddress logical address of HDMI-Cec device.
     *                       For more details, refer {@link HdmiCec}
     * @param physicalAddress physical address of HDMI-Cec device
     * @param deviceType type of device. For more details, refer {@link HdmiCec}
     * @param vendorId vendor id of device. It's used for vendor specific command
     * @param displayName name of device
     * @hide
     */
    public HdmiCecDeviceInfo(int logicalAddress, int physicalAddress, int deviceType,
            int vendorId, String displayName) {
        mLogicalAddress = logicalAddress;
        mPhysicalAddress = physicalAddress;
        mDeviceType = deviceType;
        mDisplayName = displayName;
        mVendorId = vendorId;
    }

    /**
     * Return the logical address of the device. It can have 0-15 values.
     * For more details, refer constants between {@link HdmiCec#ADDR_TV}
     * and {@link HdmiCec#ADDR_UNREGISTERED}.
     */
    public int getLogicalAddress() {
        return mLogicalAddress;
    }

    /**
     * Return the physical address of the device.
     */
    public int getPhysicalAddress() {
        return mPhysicalAddress;
    }

    /**
     * Return type of the device. For more details, refer constants between
     * {@link HdmiCec#DEVICE_TV} and {@link HdmiCec#DEVICE_INACTIVE}.
     */
    public int getDeviceType() {
        return mDeviceType;
    }

    /**
     * Return display (OSD) name of the device.
     */
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Return vendor id of the device. Vendor id is used to distinguish devices
     * built by other manufactures. This is required for vendor-specific command
     * on CEC standard.
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * Describe the kinds of special objects contained in this Parcelable's
     * marshalled representation.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Serialize this object into a {@link Parcel}.
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *        May be 0 or {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mLogicalAddress);
        dest.writeInt(mPhysicalAddress);
        dest.writeInt(mDeviceType);
        dest.writeInt(mVendorId);
        dest.writeString(mDisplayName);
    }

    @Override
    public String toString() {
        StringBuffer s = new StringBuffer();
        s.append("logical_address: ").append(mLogicalAddress).append(", ");
        s.append("physical_address: ").append(mPhysicalAddress).append(", ");
        s.append("device_type: ").append(mDeviceType).append(", ");
        s.append("vendor_id: ").append(mVendorId).append(", ");
        s.append("display_name: ").append(mDisplayName);
        return s.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HdmiCecDeviceInfo)) {
            return false;
        }

        HdmiCecDeviceInfo other = (HdmiCecDeviceInfo) obj;
        return mLogicalAddress == other.mLogicalAddress
                && mPhysicalAddress == other.mPhysicalAddress
                && mDeviceType == other.mDeviceType
                && mVendorId == other.mVendorId
                && mDisplayName.equals(other.mDisplayName);
    }
}
