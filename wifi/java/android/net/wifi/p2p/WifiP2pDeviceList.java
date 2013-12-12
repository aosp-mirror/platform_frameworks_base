/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.wifi.p2p;

import android.os.Parcelable;
import android.os.Parcel;
import android.net.wifi.p2p.WifiP2pDevice;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

/**
 * A class representing a Wi-Fi P2p device list.
 *
 * Note that the operations are not thread safe.
 * {@see WifiP2pManager}
 */
public class WifiP2pDeviceList implements Parcelable {

    private final HashMap<String, WifiP2pDevice> mDevices = new HashMap<String, WifiP2pDevice>();

    public WifiP2pDeviceList() {
    }

    /** copy constructor */
    public WifiP2pDeviceList(WifiP2pDeviceList source) {
        if (source != null) {
            for (WifiP2pDevice d : source.getDeviceList()) {
                mDevices.put(d.deviceAddress, new WifiP2pDevice(d));
            }
        }
    }

    /** @hide */
    public WifiP2pDeviceList(ArrayList<WifiP2pDevice> devices) {
        for (WifiP2pDevice device : devices) {
            if (device.deviceAddress != null) {
                mDevices.put(device.deviceAddress, new WifiP2pDevice(device));
            }
        }
    }

    private void validateDevice(WifiP2pDevice device) {
        if (device == null) throw new IllegalArgumentException("Null device");
        if (TextUtils.isEmpty(device.deviceAddress)) {
            throw new IllegalArgumentException("Empty deviceAddress");
        }
    }

    private void validateDeviceAddress(String deviceAddress) {
        if (TextUtils.isEmpty(deviceAddress)) {
            throw new IllegalArgumentException("Empty deviceAddress");
        }
    }

    /** Clear the list @hide */
    public boolean clear() {
        if (mDevices.isEmpty()) return false;
        mDevices.clear();
        return true;
    }

    /**
     * Add/update a device to the list. If the device is not found, a new device entry
     * is created. If the device is already found, the device details are updated
     * @param device to be updated
     * @hide
     */
    public void update(WifiP2pDevice device) {
        updateSupplicantDetails(device);
        mDevices.get(device.deviceAddress).status = device.status;
    }

    /** Only updates details fetched from the supplicant @hide */
    public void updateSupplicantDetails(WifiP2pDevice device) {
        validateDevice(device);
        WifiP2pDevice d = mDevices.get(device.deviceAddress);
        if (d != null) {
            d.deviceName = device.deviceName;
            d.primaryDeviceType = device.primaryDeviceType;
            d.secondaryDeviceType = device.secondaryDeviceType;
            d.wpsConfigMethodsSupported = device.wpsConfigMethodsSupported;
            d.deviceCapability = device.deviceCapability;
            d.groupCapability = device.groupCapability;
            d.wfdInfo = device.wfdInfo;
            return;
        }
        //Not found, add a new one
        mDevices.put(device.deviceAddress, device);
    }

    /** @hide */
    public void updateGroupCapability(String deviceAddress, int groupCapab) {
        validateDeviceAddress(deviceAddress);
        WifiP2pDevice d = mDevices.get(deviceAddress);
        if (d != null) {
            d.groupCapability = groupCapab;
        }
    }

    /** @hide */
    public void updateStatus(String deviceAddress, int status) {
        validateDeviceAddress(deviceAddress);
        WifiP2pDevice d = mDevices.get(deviceAddress);
        if (d != null) {
            d.status = status;
        }
    }

    /**
     * Fetch a device from the list
     * @param deviceAddress is the address of the device
     * @return WifiP2pDevice device found, or null if none found
     */
    public WifiP2pDevice get(String deviceAddress) {
        validateDeviceAddress(deviceAddress);
        return mDevices.get(deviceAddress);
    }

    /** @hide */
    public boolean remove(WifiP2pDevice device) {
        validateDevice(device);
        return mDevices.remove(device.deviceAddress) != null;
    }

    /**
     * Remove a device from the list
     * @param deviceAddress is the address of the device
     * @return WifiP2pDevice device removed, or null if none removed
     * @hide
     */
    public WifiP2pDevice remove(String deviceAddress) {
        validateDeviceAddress(deviceAddress);
        return mDevices.remove(deviceAddress);
    }

    /** Returns true if any device the list was removed @hide */
    public boolean remove(WifiP2pDeviceList list) {
        boolean ret = false;
        for (WifiP2pDevice d : list.mDevices.values()) {
            if (remove(d)) ret = true;
        }
        return ret;
    }

    /** Get the list of devices */
    public Collection<WifiP2pDevice> getDeviceList() {
        return Collections.unmodifiableCollection(mDevices.values());
    }

    /** @hide */
    public boolean isGroupOwner(String deviceAddress) {
        validateDeviceAddress(deviceAddress);
        WifiP2pDevice device = mDevices.get(deviceAddress);
        if (device == null) {
            throw new IllegalArgumentException("Device not found " + deviceAddress);
        }
        return device.isGroupOwner();
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        for (WifiP2pDevice device : mDevices.values()) {
            sbuf.append("\n").append(device);
        }
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDevices.size());
        for(WifiP2pDevice device : mDevices.values()) {
            dest.writeParcelable(device, flags);
        }
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pDeviceList> CREATOR =
        new Creator<WifiP2pDeviceList>() {
            public WifiP2pDeviceList createFromParcel(Parcel in) {
                WifiP2pDeviceList deviceList = new WifiP2pDeviceList();

                int deviceCount = in.readInt();
                for (int i = 0; i < deviceCount; i++) {
                    deviceList.update((WifiP2pDevice)in.readParcelable(null));
                }
                return deviceList;
            }

            public WifiP2pDeviceList[] newArray(int size) {
                return new WifiP2pDeviceList[size];
            }
        };
}
