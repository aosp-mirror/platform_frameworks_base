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
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * A class representing a Wi-Fi P2p device list
 *
 * {@see WifiP2pManager}
 */
public class WifiP2pDeviceList implements Parcelable {

    private Collection<WifiP2pDevice> mDevices;

    public WifiP2pDeviceList() {
        mDevices = new ArrayList<WifiP2pDevice>();
    }

    /** copy constructor */
    public WifiP2pDeviceList(WifiP2pDeviceList source) {
        if (source != null) {
            mDevices = source.getDeviceList();
        }
    }

    /** @hide */
    public WifiP2pDeviceList(ArrayList<WifiP2pDevice> devices) {
        mDevices = new ArrayList<WifiP2pDevice>();
        for (WifiP2pDevice device : devices) {
            mDevices.add(device);
        }
    }

    /** @hide */
    public boolean clear() {
        if (mDevices.isEmpty()) return false;
        mDevices.clear();
        return true;
    }

    /** @hide */
    public void update(WifiP2pDevice device) {
        if (device == null) return;
        for (WifiP2pDevice d : mDevices) {
            //Found, update fields that can change
            if (d.equals(device)) {
                d.deviceName = device.deviceName;
                d.primaryDeviceType = device.primaryDeviceType;
                d.secondaryDeviceType = device.secondaryDeviceType;
                d.wpsConfigMethodsSupported = device.wpsConfigMethodsSupported;
                d.deviceCapability = device.deviceCapability;
                d.groupCapability = device.groupCapability;
                return;
            }
        }
        //Not found, add a new one
        mDevices.add(device);
    }

    /** @hide */
    public void updateInterfaceAddress(WifiP2pDevice device) {
        for (WifiP2pDevice d : mDevices) {
            //Found, update interface address
            if (d.equals(device)) {
                d.interfaceAddress = device.interfaceAddress;
                return;
            }
        }
        //Not found, add a new one
        mDevices.add(device);
    }

    /** @hide */
    public boolean remove(WifiP2pDevice device) {
        if (device == null) return false;
        return mDevices.remove(device);
    }

    /** Get the list of devices */
    public Collection<WifiP2pDevice> getDeviceList() {
        return Collections.unmodifiableCollection(mDevices);
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        for (WifiP2pDevice device : mDevices) {
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
        for(WifiP2pDevice device : mDevices) {
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
