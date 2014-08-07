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

package android.net.wifi;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

/**
 * Represents local wifi adapter. Different devices have different kinds of
 * wifi adapters; each with different capabilities. Use this class to find out
 * which capabilites are supported by the wifi adapter on the device.
 */
public class WifiAdapter implements Parcelable {
    private static final String TAG = "WifiAdapter";

    /* Keep this list in sync with wifi_hal.h */
    /** @hide */
    public static final int WIFI_FEATURE_INFRA            = 0x0001;  // Basic infrastructure mode
    /** @hide */
    public static final int WIFI_FEATURE_INFRA_5G         = 0x0002;  // Support for 5 GHz Band
    /** @hide */
    public static final int WIFI_FEATURE_PASSPOINT        = 0x0004;  // Support for GAS/ANQP
    /** @hide */
    public static final int WIFI_FEATURE_P2P              = 0x0008;  // Wifi-Direct
    /** @hide */
    public static final int WIFI_FEATURE_MOBILE_HOTSPOT   = 0x0010;  // Soft AP
    /** @hide */
    public static final int WIFI_FEATURE_SCANNER          = 0x0020;  // WifiScanner APIs
    /** @hide */
    public static final int WIFI_FEATURE_NAN              = 0x0040;  // Neighbor Awareness Networking
    /** @hide */
    public static final int WIFI_FEATURE_D2D_RTT          = 0x0080;  // Device-to-device RTT
    /** @hide */
    public static final int WIFI_FEATURE_D2AP_RTT         = 0x0100;  // Device-to-AP RTT
    /** @hide */
    public static final int WIFI_FEATURE_BATCH_SCAN       = 0x0200;  // Batched Scan (deprecated)
    /** @hide */
    public static final int WIFI_FEATURE_PNO              = 0x0400;  // Preferred network offload
    /** @hide */
    public static final int WIFI_FEATURE_ADDITIONAL_STA   = 0x0800;  // Support for two STAs
    /** @hide */
    public static final int WIFI_FEATURE_TDLS             = 0x1000;  // Tunnel directed link setup
    /** @hide */
    public static final int WIFI_FEATURE_TDLS_OFFCHANNEL  = 0x2000;  // Support for TDLS off channel
    /** @hide */
    public static final int WIFI_FEATURE_EPR              = 0x4000;  // Enhanced power reporting

    private static final int CONTROLLER_ENERGY_UPDATE_TIMEOUT_MILLIS = 30;
    /** @hide */
    public static final int ACTIVITY_ENERGY_INFO_CACHED = 0;
    /** @hide */
    public static final int ACTIVITY_ENERGY_INFO_REFRESHED = 1;

    private String name;
    private int    supportedFeatures;

    // Make the API consistent with BlueTooth Adaptor, allowing WifiService to be accessed
    // Directly from the adapter
    /** @hide */
    public IWifiManager mService = null;

    /** @hide */
    public WifiAdapter(String name, int supportedFeatures) {
        this.name = name;
        this.supportedFeatures = supportedFeatures;
    }

    /**
     * @return name of the adapter
     */
    public String getName() {
        return name;
    }

    private int getSupportedFeatures() {
        return supportedFeatures;
    }

    private boolean isFeatureSupported(int feature) {
        return (supportedFeatures & feature) == feature;
    }

    /**
     * @return true if this adapter supports 5 GHz band
     */
    public boolean is5GHzBandSupported() {
        return isFeatureSupported(WIFI_FEATURE_INFRA_5G);
    }

    /**
     * @return true if this adapter supports passpoint
     */
    public boolean isPasspointSupported() {
        return isFeatureSupported(WIFI_FEATURE_PASSPOINT);
    }

    /**
     * @return true if this adapter supports WifiP2pManager (Wi-Fi Direct)
     */
    public boolean isP2pSupported() {
        return isFeatureSupported(WIFI_FEATURE_P2P);
    }

    /**
     * @return true if this adapter supports portable Wi-Fi hotspot
     */
    public boolean isPortableHotspotSupported() {
        return isFeatureSupported(WIFI_FEATURE_MOBILE_HOTSPOT);
    }

    /**
     * @return true if this adapter supports WifiScanner APIs
     */
    public boolean isWifiScannerSupported() {
        return isFeatureSupported(WIFI_FEATURE_SCANNER);
    }

    /**
     * @return true if this adapter supports Neighbour Awareness Network APIs
     * @hide
     */
    public boolean isNanSupported() {
        return isFeatureSupported(WIFI_FEATURE_NAN);
    }

    /**
     * @return true if this adapter supports Device-to-device RTT
     */
    public boolean isDeviceToDeviceRttSupported() {
        return isFeatureSupported(WIFI_FEATURE_D2D_RTT);
    }

    /**
     * @return true if this adapter supports Device-to-AP RTT
     */
    public boolean isDeviceToApRttSupported() {
        return isFeatureSupported(WIFI_FEATURE_D2AP_RTT);
    }

    /**
     * @return true if this adapter supports offloaded connectivity scan
     */
    public boolean isPreferredNetworkOffloadSupported() {
        return isFeatureSupported(WIFI_FEATURE_PNO);
    }

    /**
     * @return true if this adapter supports multiple simultaneous connections
     * @hide
     */
    public boolean isAdditionalStaSupported() {
        return isFeatureSupported(WIFI_FEATURE_ADDITIONAL_STA);
    }

    /**
     * @return true if this adapter supports Tunnel Directed Link Setup
     */
    public boolean isTdlsSupported() {
        return isFeatureSupported(WIFI_FEATURE_TDLS);
    }

    /**
     * @return true if this adapter supports Off Channel Tunnel Directed Link Setup
     */
    public boolean isOffChannelTdlsSupported() {
        return isFeatureSupported(WIFI_FEATURE_TDLS_OFFCHANNEL);
    }

    /**
     * @return true if this adapter supports advanced power/performance counters
     */
    public boolean isEnhancedPowerReportingSupported() {
        return isFeatureSupported(WIFI_FEATURE_EPR);
    }


    /**
     * Return the record of {@link WifiActivityEnergyInfo} object that
     * has the activity and energy info. This can be used to ascertain what
     * the controller has been up to, since the last sample.
     * @param updateType Type of info, cached vs refreshed.
     *
     * @return a record with {@link WifiActivityEnergyInfo} or null if
     * report is unavailable or unsupported
     * @hide
     */
    public WifiActivityEnergyInfo getControllerActivityEnergyInfo(int updateType) {
        if (mService == null) return null;
        try {
            WifiActivityEnergyInfo record;
            if (!isEnhancedPowerReportingSupported()) {
                return null;
            }
            synchronized(this) {
                record = mService.reportActivityInfo(this);
                if (record.isValid()) {
                    return record;
                } else {
                    return null;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getControllerActivityEnergyInfo: " + e);
        }
        return null;
    }

    /* Parcelable implementation */
    /**
     * Implement the Parcelable interface
     * {@hide}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface
     * {@hide}
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeInt(supportedFeatures);
    }

    /**
     * Implement the Parcelable interface
     * {@hide}
     */
    public static final Creator<WifiAdapter> CREATOR =
            new Creator<WifiAdapter>() {
                public WifiAdapter createFromParcel(Parcel in) {
                    WifiAdapter adaptor = new WifiAdapter(in.readString(), in.readInt());
                    return adaptor;
                }

                public WifiAdapter[] newArray(int size) {
                    return new WifiAdapter[size];
                }
            };
}
