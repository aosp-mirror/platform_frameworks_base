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

import com.android.settingslib.net.DataUsageController;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;

public interface NetworkController extends CallbackController<SignalCallback>, DemoMode {

    boolean hasMobileDataFeature();
    void setWifiEnabled(boolean enabled);
    AccessPointController getAccessPointController();
    DataUsageController getMobileDataController();
    DataSaverController getDataSaverController();
    String getMobileDataNetworkName();
    boolean isMobileDataNetworkInService();
    int getNumberSubscriptions();

    boolean hasVoiceCallingFeature();

    void addEmergencyListener(EmergencyListener listener);
    void removeEmergencyListener(EmergencyListener listener);
    boolean hasEmergencyCryptKeeperText();

    boolean isRadioOn();

    /**
     * Wrapper class for all the WiFi signals used for WiFi indicators.
     */
    final class WifiIndicators {
        public boolean enabled;
        public IconState statusIcon;
        public IconState qsIcon;
        public boolean activityIn;
        public boolean activityOut;
        public String description;
        public boolean isTransient;
        public String statusLabel;

        public WifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
                boolean activityIn, boolean activityOut, String description,
                boolean isTransient, String statusLabel) {
            this.enabled = enabled;
            this.statusIcon = statusIcon;
            this.qsIcon = qsIcon;
            this.activityIn = activityIn;
            this.activityOut = activityOut;
            this.description = description;
            this.isTransient = isTransient;
            this.statusLabel = statusLabel;
        }

        @Override
        public String toString() {
            return new StringBuilder("WifiIndicators[")
                .append("enabled=").append(enabled)
                .append(",statusIcon=").append(statusIcon == null ? "" : statusIcon.toString())
                .append(",qsIcon=").append(qsIcon == null ? "" : qsIcon.toString())
                .append(",activityIn=").append(activityIn)
                .append(",activityOut=").append(activityOut)
                .append(",description=").append(description)
                .append(",isTransient=").append(isTransient)
                .append(",statusLabel=").append(statusLabel)
                .append(']').toString();
        }
    }

    /**
     * Wrapper class for all the mobile signals used for mobile data indicators.
     */
    final class MobileDataIndicators {
        public IconState statusIcon;
        public IconState qsIcon;
        public int statusType;
        public int qsType;
        public boolean activityIn;
        public boolean activityOut;
        public CharSequence typeContentDescription;
        public CharSequence typeContentDescriptionHtml;
        public CharSequence description;
        public boolean isWide;
        public int subId;
        public boolean roaming;
        public boolean showTriangle;

        public MobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
                int qsType, boolean activityIn, boolean activityOut,
                CharSequence typeContentDescription, CharSequence typeContentDescriptionHtml,
                CharSequence description, boolean isWide, int subId, boolean roaming,
                boolean showTriangle) {
            this.statusIcon = statusIcon;
            this.qsIcon = qsIcon;
            this.statusType = statusType;
            this.qsType = qsType;
            this.activityIn = activityIn;
            this.activityOut = activityOut;
            this.typeContentDescription = typeContentDescription;
            this.typeContentDescriptionHtml = typeContentDescriptionHtml;
            this.description = description;
            this.isWide = isWide;
            this.subId = subId;
            this.roaming = roaming;
            this.showTriangle = showTriangle;
        }

        @Override
        public String toString() {
            return new StringBuilder("MobileDataIndicators[")
                .append("statusIcon=").append(statusIcon == null ? "" :  statusIcon.toString())
                .append(",qsIcon=").append(qsIcon == null ? "" : qsIcon.toString())
                .append(",statusType=").append(statusType)
                .append(",qsType=").append(qsType)
                .append(",activityIn=").append(activityIn)
                .append(",activityOut=").append(activityOut)
                .append(",typeContentDescription=").append(typeContentDescription)
                .append(",typeContentDescriptionHtml=").append(typeContentDescriptionHtml)
                .append(",description=").append(description)
                .append(",isWide=").append(isWide)
                .append(",subId=").append(subId)
                .append(",roaming=").append(roaming)
                .append(",showTriangle=").append(showTriangle)
                .append(']').toString();
        }
    }

    public interface SignalCallback {
        /**
         * Callback for listeners to be able to update the state of any UI tracking connectivity of
         * WiFi networks.
         */
        default void setWifiIndicators(WifiIndicators wifiIndicators) {}

        /**
         * Callback for listeners to be able to update the state of any UI tracking connectivity
         * of Mobile networks.
         */
        default void setMobileDataIndicators(MobileDataIndicators mobileDataIndicators) {}

        default void setSubs(List<SubscriptionInfo> subs) {}

        default void setNoSims(boolean show, boolean simDetected) {}

        default void setEthernetIndicators(IconState icon) {}

        default void setIsAirplaneMode(IconState icon) {}

        default void setMobileDataEnabled(boolean enabled) {}

        /**
         * Callback for listeners to be able to update the connectivity status
         * @param noDefaultNetwork whether there is any default network.
         * @param noValidatedNetwork whether there is any validated network.
         * @param noNetworksAvailable whether there is any WiFi networks available.
         */
        default void setConnectivityStatus(boolean noDefaultNetwork, boolean noValidatedNetwork,
                boolean noNetworksAvailable) {}

        /**
         * Callback for listeners to be able to update the call indicator
         * @param statusIcon the icon for the call indicator
         * @param subId subscription ID for which to update the UI
         */
        default void setCallIndicator(IconState statusIcon, int subId) {}
    }

    public interface EmergencyListener {
        void setEmergencyCallsOnly(boolean emergencyOnly);
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

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            return builder.append("[visible=").append(visible).append(',')
                .append("icon=").append(icon).append(',')
                .append("contentDescription=").append(contentDescription).append(']')
                .toString();
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
        int getIcon(WifiEntry ap);
        boolean connect(WifiEntry ap);
        boolean canConfigWifi();

        public interface AccessPointCallback {
            void onAccessPointsChanged(List<WifiEntry> accessPoints);
            void onSettingsActivityTriggered(Intent settingsIntent);
        }
    }
}
