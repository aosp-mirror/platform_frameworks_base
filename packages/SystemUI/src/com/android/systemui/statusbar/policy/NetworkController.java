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

import android.content.Context;
import android.content.Intent;
import android.telephony.SubscriptionInfo;

import com.android.settingslib.wifi.AccessPoint;

import java.util.List;

public interface NetworkController {

    boolean hasMobileDataFeature();
    void addSignalCallback(SignalCallback cb);
    void removeSignalCallback(SignalCallback cb);
    void setWifiEnabled(boolean enabled);
    void onUserSwitched(int newUserId);
    AccessPointController getAccessPointController();
    MobileDataController getMobileDataController();

    public interface SignalCallback {
        void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
                boolean activityIn, boolean activityOut, String description);

        void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
                int qsType, boolean activityIn, boolean activityOut, String typeContentDescription,
                String description, boolean isWide, int subId);
        void setSubs(List<SubscriptionInfo> subs);
        void setNoSims(boolean show);

        void setEthernetIndicators(IconState icon);

        void setIsAirplaneMode(IconState icon);

        void setMobileDataEnabled(boolean enabled);
    }

    public static class IconState {
        public final boolean visible;
        public final int icon;
        public final String contentDescription;

        public IconState(boolean visible, int icon, String contentDescription) {
            this.visible = visible;
            this.icon = icon;
            this.contentDescription = contentDescription;
        }

        public IconState(boolean visible, int icon, int contentDescription,
                Context context) {
            this(visible, icon, context.getString(contentDescription));
        }
    }

    /**
     * Tracks changes in access points.  Allows listening for changes, scanning for new APs,
     * and connecting to new ones.
     */
    public interface AccessPointController {
        void addAccessPointCallback(AccessPointCallback callback);
        void removeAccessPointCallback(AccessPointCallback callback);
        void scanForAccessPoints();
        int getIcon(AccessPoint ap);
        boolean connect(AccessPoint ap);
        boolean canConfigWifi();

        public interface AccessPointCallback {
            void onAccessPointsChanged(List<AccessPoint> accessPoints);
            void onSettingsActivityTriggered(Intent settingsIntent);
        }
    }

    /**
     * Tracks mobile data support and usage.
     */
    public interface MobileDataController {
        boolean isMobileDataSupported();
        boolean isMobileDataEnabled();
        void setMobileDataEnabled(boolean enabled);
        DataUsageInfo getDataUsageInfo();

        public static class DataUsageInfo {
            public String carrier;
            public String period;
            public long limitLevel;
            public long warningLevel;
            public long usageLevel;
        }
    }
}
