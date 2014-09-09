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

package com.android.systemui.statusbar.policy;

public interface NetworkController {

    boolean hasMobileDataFeature();
    void addNetworkSignalChangedCallback(NetworkSignalChangedCallback cb);
    void removeNetworkSignalChangedCallback(NetworkSignalChangedCallback cb);
    void setWifiEnabled(boolean enabled);

    public interface NetworkSignalChangedCallback {
        void onWifiSignalChanged(boolean enabled, boolean connected, int wifiSignalIconId,
                boolean activityIn, boolean activityOut,
                String wifiSignalContentDescriptionId, String description);
        void onMobileDataSignalChanged(boolean enabled, int mobileSignalIconId,
                String mobileSignalContentDescriptionId, int dataTypeIconId,
                boolean activityIn, boolean activityOut,
                String dataTypeContentDescriptionId, String description, boolean noSim,
                boolean isDataTypeIconWide);
        void onAirplaneModeChanged(boolean enabled);
        void onMobileDataEnabled(boolean enabled);
    }

    void addAccessPointCallback(AccessPointCallback callback);
    void removeAccessPointCallback(AccessPointCallback callback);
    void scanForAccessPoints();
    void connect(AccessPoint ap);
    boolean isMobileDataSupported();
    boolean isMobileDataEnabled();
    void setMobileDataEnabled(boolean enabled);
    DataUsageInfo getDataUsageInfo();

    public interface AccessPointCallback {
        void onAccessPointsChanged(AccessPoint[] accessPoints);
    }

    public static class AccessPoint {
        public static final int NO_NETWORK = -1;  // see WifiManager

        public int networkId;
        public int iconId;
        public String ssid;
        public boolean isConnected;
        public int level;  // 0 - 5
    }

    public static class DataUsageInfo {
        public String carrier;
        public String period;
        public long limitLevel;
        public long warningLevel;
        public long usageLevel;
    }
}
