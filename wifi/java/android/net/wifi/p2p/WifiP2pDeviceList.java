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
 * @hide
 */
public class WifiP2pDeviceList implements Parcelable {

    private Collection<WifiP2pDevice> mDevices;

    public WifiP2pDeviceList() {
        mDevices = new ArrayList<WifiP2pDevice>();
    }

    //copy constructor
    public WifiP2pDeviceList(WifiP2pDeviceList source) {
        if (source != null) {
            mDevices = source.getDeviceList();
        }
    }

    public WifiP2pDeviceList(ArrayList<WifiP2pDevice> devices) {
        mDevices = new ArrayList<WifiP2pDevice>();
        for (WifiP2pDevice device : devices) {
            mDevices.add(device);
        }
    }

    public void clear() {
        mDevices.clear();
    }

    public void add(WifiP2pDevice device) {
        if (device == null) return;
        if (mDevices.contains(device)) return;
        mDevices.add(device);
    }

    public boolean remove(WifiP2pDevice device) {
        if (device == null) return false;
        return mDevices.remove(device);
    }

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

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDevices.size());
        for(WifiP2pDevice device : mDevices) {
            dest.writeParcelable(device, flags);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiP2pDeviceList> CREATOR =
        new Creator<WifiP2pDeviceList>() {
            public WifiP2pDeviceList createFromParcel(Parcel in) {
                WifiP2pDeviceList deviceList = new WifiP2pDeviceList();

                int deviceCount = in.readInt();
                for (int i = 0; i < deviceCount; i++) {
                    deviceList.add((WifiP2pDevice)in.readParcelable(null));
                }
                return deviceList;
            }

            public WifiP2pDeviceList[] newArray(int size) {
                return new WifiP2pDeviceList[size];
            }
        };
}
