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

/** @hide */
public class WifiAdapter implements Parcelable {

    /* Keep this list in sync with wifi_hal.h */
    public static final int WIFI_FEATURE_INFRA            = 0x0001;  // Basic infrastructure mode
    public static final int WIFI_FEATURE_INFRA_5G         = 0x0002;  // Support for 5 GHz Band
    public static final int WIFI_FEATURE_PASSPOINT        = 0x0004;  // Support for GAS/ANQP
    public static final int WIFI_FEATURE_P2P              = 0x0008;  // Wifi-Direct
    public static final int WIFI_FEATURE_MOBILE_HOTSPOT   = 0x0010;  // Soft AP
    public static final int WIFI_FEATURE_SCANNER          = 0x0020;  // WifiScanner APIs
    public static final int WIFI_FEATURE_NAN              = 0x0040;  // Neighbor Awareness Networking
    public static final int WIFI_FEATURE_D2D_RTT          = 0x0080;  // Device-to-device RTT
    public static final int WIFI_FEATURE_D2AP_RTT         = 0x0100;  // Device-to-AP RTT
    public static final int WIFI_FEATURE_BATCH_SCAN       = 0x0200;  // Batched Scan (deprecated)
    public static final int WIFI_FEATURE_PNO              = 0x0400;  // Preferred network offload
    public static final int WIFI_FEATURE_ADDITIONAL_STA   = 0x0800;  // Support for two STAs
    public static final int WIFI_FEATURE_TDLS             = 0x1000;  // Tunnel directed link setup
    public static final int WIFI_FEATURE_TDLS_OFFCHANNEL  = 0x2000;  // Support for TDLS off channel
    public static final int WIFI_FEATURE_EPR              = 0x4000;  // Enhanced power reporting

    private String name;
    private int    supportedFeatures;

    public WifiAdapter(String name, int supportedFeatures) {
        this.name = name;
        this.supportedFeatures = supportedFeatures;
    }

    public String getName() {
        return name;
    }

    private int getSupportedFeatures() {
        return supportedFeatures;
    }

    private boolean isFeatureSupported(int feature) {
        return (supportedFeatures & feature) == feature;
    }

    public boolean isPasspointSupported() {
        return isFeatureSupported(WIFI_FEATURE_PASSPOINT);
    }

    public boolean isWifiDirectSupported() {
        return isFeatureSupported(WIFI_FEATURE_P2P);
    }

    public boolean isMobileHotstpoSupported() {
        return isFeatureSupported(WIFI_FEATURE_MOBILE_HOTSPOT);
    }

    public boolean isWifiScannerSupported() {
        return isFeatureSupported(WIFI_FEATURE_SCANNER);
    }

    public boolean isNanSupported() {
        return isFeatureSupported(WIFI_FEATURE_NAN);
    }

    public boolean isDeviceToDeviceRttSupported() {
        return isFeatureSupported(WIFI_FEATURE_D2D_RTT);
    }

    public boolean isDeviceToApRttSupported() {
        return isFeatureSupported(WIFI_FEATURE_D2AP_RTT);
    }

    public boolean isPreferredNetworkOffloadSupported() {
        return isFeatureSupported(WIFI_FEATURE_PNO);
    }

    public boolean isAdditionalStaSupported() {
        return isFeatureSupported(WIFI_FEATURE_ADDITIONAL_STA);
    }

    public boolean isTdlsSupported() {
        return isFeatureSupported(WIFI_FEATURE_TDLS);
    }

    public boolean isOffChannelTdlsSupported() {
        return isFeatureSupported(WIFI_FEATURE_TDLS_OFFCHANNEL);
    }

    public boolean isEnhancedPowerReportingSupported() {
        return isFeatureSupported(WIFI_FEATURE_EPR);
    }

    /* Parcelable implementation */

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeInt(supportedFeatures);
    }

    /** Implement the Parcelable interface {@hide} */
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
