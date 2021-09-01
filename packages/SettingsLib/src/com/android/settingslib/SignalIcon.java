/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.settingslib;

import com.android.settingslib.mobile.TelephonyIcons;

import java.text.SimpleDateFormat;
import java.util.Objects;

/**
 * Icons and states for SysUI and Settings.
 */
public class SignalIcon {

    /**
     * Holds icons for a given state. Arrays are generally indexed as inet
     * state (full connectivity or not) first, and second dimension as
     * signal strength.
     */
    public static class IconGroup {
        public final int[][] sbIcons;
        public final int[][] qsIcons;
        public final int[] contentDesc;
        public final int sbNullState;
        public final int qsNullState;
        public final int sbDiscState;
        public final int qsDiscState;
        public final int discContentDesc;
        // For logging.
        public final String name;

        public IconGroup(
                String name,
                int[][] sbIcons,
                int[][] qsIcons,
                int[] contentDesc,
                int sbNullState,
                int qsNullState,
                int sbDiscState,
                int qsDiscState,
                int discContentDesc
        ) {
            this.name = name;
            this.sbIcons = sbIcons;
            this.qsIcons = qsIcons;
            this.contentDesc = contentDesc;
            this.sbNullState = sbNullState;
            this.qsNullState = qsNullState;
            this.sbDiscState = sbDiscState;
            this.qsDiscState = qsDiscState;
            this.discContentDesc = discContentDesc;
        }

        @Override
        public String toString() {
            return "IconGroup(" + name + ")";
        }
    }

    /**
     * Holds states for SysUI.
     */
    public static class State {
        // No locale as it's only used for logging purposes
        private static SimpleDateFormat sSDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
        public boolean connected;
        public boolean enabled;
        public boolean activityIn;
        public boolean activityOut;
        public int level;
        public IconGroup iconGroup;
        public int inetCondition;
        public int rssi; // Only for logging.

        // Not used for comparison, just used for logging.
        public long time;

        /**
         * Generates a copy of the source state.
         */
        public void copyFrom(State state) {
            connected = state.connected;
            enabled = state.enabled;
            level = state.level;
            iconGroup = state.iconGroup;
            inetCondition = state.inetCondition;
            activityIn = state.activityIn;
            activityOut = state.activityOut;
            rssi = state.rssi;
            time = state.time;
        }

        @Override
        public String toString() {
            if (time != 0) {
                StringBuilder builder = new StringBuilder();
                toString(builder);
                return builder.toString();
            } else {
                return "Empty " + getClass().getSimpleName();
            }
        }

        protected void toString(StringBuilder builder) {
            builder.append("connected=").append(connected).append(',')
                .append("enabled=").append(enabled).append(',')
                .append("level=").append(level).append(',')
                .append("inetCondition=").append(inetCondition).append(',')
                .append("iconGroup=").append(iconGroup).append(',')
                .append("activityIn=").append(activityIn).append(',')
                .append("activityOut=").append(activityOut).append(',')
                .append("rssi=").append(rssi).append(',')
                .append("lastModified=").append(sSDF.format(time));
        }

        @Override
        public boolean equals(Object o) {
            if (!o.getClass().equals(getClass())) {
                return false;
            }
            State other = (State) o;
            return other.connected == connected
                    && other.enabled == enabled
                    && other.level == level
                    && other.inetCondition == inetCondition
                    && other.iconGroup == iconGroup
                    && other.activityIn == activityIn
                    && other.activityOut == activityOut
                    && other.rssi == rssi;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    connected,
                    enabled,
                    level,
                    inetCondition,
                    iconGroup,
                    activityIn,
                    activityOut,
                    rssi);
        }
    }

    /**
     * Holds icons for a given MobileState.
     */
    public static class MobileIconGroup extends IconGroup {
        public final int dataContentDescription; // mContentDescriptionDataType
        public final int dataType;

        public MobileIconGroup(
                String name,
                int[][] sbIcons,
                int[][] qsIcons,
                int[] contentDesc,
                int sbNullState,
                int qsNullState,
                int sbDiscState,
                int qsDiscState,
                int discContentDesc,
                int dataContentDesc,
                int dataType
        ) {
            super(name,
                    sbIcons,
                    qsIcons,
                    contentDesc,
                    sbNullState,
                    qsNullState,
                    sbDiscState,
                    qsDiscState,
                    discContentDesc);
            this.dataContentDescription = dataContentDesc;
            this.dataType = dataType;
        }
    }

    /**
     * Holds mobile states for SysUI.
     */
    public static class MobileState extends State {
        public String networkName;
        public String networkNameData;
        public boolean dataSim;
        public boolean dataConnected;
        public boolean isEmergency;
        public boolean airplaneMode;
        public boolean carrierNetworkChangeMode;
        public boolean isDefault;
        public boolean userSetup;
        public boolean roaming;
        public boolean defaultDataOff;  // Tracks the on/off state of the defaultDataSubscription

        @Override
        public void copyFrom(State s) {
            super.copyFrom(s);
            MobileState state = (MobileState) s;
            dataSim = state.dataSim;
            networkName = state.networkName;
            networkNameData = state.networkNameData;
            dataConnected = state.dataConnected;
            isDefault = state.isDefault;
            isEmergency = state.isEmergency;
            airplaneMode = state.airplaneMode;
            carrierNetworkChangeMode = state.carrierNetworkChangeMode;
            userSetup = state.userSetup;
            roaming = state.roaming;
            defaultDataOff = state.defaultDataOff;
        }

        /** @return true if this state is disabled or not default data */
        public boolean isDataDisabledOrNotDefault() {
            return (iconGroup == TelephonyIcons.DATA_DISABLED
                    || (iconGroup == TelephonyIcons.NOT_DEFAULT_DATA)) && userSetup;
        }

        /** @return if this state is considered to have inbound activity */
        public boolean hasActivityIn() {
            return dataConnected && !carrierNetworkChangeMode && activityIn;
        }

        /** @return if this state is considered to have outbound activity */
        public boolean hasActivityOut() {
            return dataConnected && !carrierNetworkChangeMode && activityOut;
        }

        @Override
        protected void toString(StringBuilder builder) {
            super.toString(builder);
            builder.append(',');
            builder.append("dataSim=").append(dataSim).append(',');
            builder.append("networkName=").append(networkName).append(',');
            builder.append("networkNameData=").append(networkNameData).append(',');
            builder.append("dataConnected=").append(dataConnected).append(',');
            builder.append("roaming=").append(roaming).append(',');
            builder.append("isDefault=").append(isDefault).append(',');
            builder.append("isEmergency=").append(isEmergency).append(',');
            builder.append("airplaneMode=").append(airplaneMode).append(',');
            builder.append("carrierNetworkChangeMode=").append(carrierNetworkChangeMode)
                    .append(',');
            builder.append("userSetup=").append(userSetup).append(',');
            builder.append("defaultDataOff=").append(defaultDataOff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o)
                    && Objects.equals(((MobileState) o).networkName, networkName)
                    && Objects.equals(((MobileState) o).networkNameData, networkNameData)
                    && ((MobileState) o).dataSim == dataSim
                    && ((MobileState) o).dataConnected == dataConnected
                    && ((MobileState) o).isEmergency == isEmergency
                    && ((MobileState) o).airplaneMode == airplaneMode
                    && ((MobileState) o).carrierNetworkChangeMode == carrierNetworkChangeMode
                    && ((MobileState) o).userSetup == userSetup
                    && ((MobileState) o).isDefault == isDefault
                    && ((MobileState) o).roaming == roaming
                    && ((MobileState) o).defaultDataOff == defaultDataOff;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(),
                    networkName,
                    networkNameData,
                    dataSim,
                    dataConnected,
                    isEmergency,
                    airplaneMode,
                    carrierNetworkChangeMode,
                    userSetup,
                    isDefault,
                    roaming,
                    defaultDataOff);
        }
    }
}
