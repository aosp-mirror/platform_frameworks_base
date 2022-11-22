/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.mobile.dataservice;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = DataServiceUtils.MobileNetworkInfoData.TABLE_NAME)
public class MobileNetworkInfoEntity {

    public MobileNetworkInfoEntity(@NonNull String subId, boolean isContactDiscoveryEnabled,
            boolean isContactDiscoveryVisible, boolean isMobileDataEnabled, boolean isCdmaOptions,
            boolean isGsmOptions, boolean isWorldMode, boolean shouldDisplayNetworkSelectOptions,
            boolean isTdscdmaSupported, boolean activeNetworkIsCellular,
            boolean showToggleForPhysicalSim, boolean isDataRoamingEnabled) {
        this.subId = subId;
        this.isContactDiscoveryEnabled = isContactDiscoveryEnabled;
        this.isContactDiscoveryVisible = isContactDiscoveryVisible;
        this.isMobileDataEnabled = isMobileDataEnabled;
        this.isCdmaOptions = isCdmaOptions;
        this.isGsmOptions = isGsmOptions;
        this.isWorldMode = isWorldMode;
        this.shouldDisplayNetworkSelectOptions = shouldDisplayNetworkSelectOptions;
        this.isTdscdmaSupported = isTdscdmaSupported;
        this.activeNetworkIsCellular = activeNetworkIsCellular;
        this.showToggleForPhysicalSim = showToggleForPhysicalSim;
        this.isDataRoamingEnabled = isDataRoamingEnabled;
    }

    @PrimaryKey
    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_ID, index = true)
    @NonNull
    public String subId;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_CONTACT_DISCOVERY_ENABLED)
    public boolean isContactDiscoveryEnabled;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_CONTACT_DISCOVERY_VISIBLE)
    public boolean isContactDiscoveryVisible;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_MOBILE_DATA_ENABLED)
    public boolean isMobileDataEnabled;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_CDMA_OPTIONS)
    public boolean isCdmaOptions;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_GSM_OPTIONS)
    public boolean isGsmOptions;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_WORLD_MODE)
    public boolean isWorldMode;

    @ColumnInfo(name =
            DataServiceUtils.MobileNetworkInfoData.COLUMN_SHOULD_DISPLAY_NETWORK_SELECT_OPTIONS)
    public boolean shouldDisplayNetworkSelectOptions;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_TDSCDMA_SUPPORTED)
    public boolean isTdscdmaSupported;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_ACTIVE_NETWORK_IS_CELLULAR)
    public boolean activeNetworkIsCellular;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_SHOW_TOGGLE_FOR_PHYSICAL_SIM)
    public boolean showToggleForPhysicalSim;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_DATA_ROAMING_ENABLED)
    public boolean isDataRoamingEnabled;

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + subId.hashCode();
        result = 31 * result + Boolean.hashCode(isContactDiscoveryEnabled);
        result = 31 * result + Boolean.hashCode(isContactDiscoveryVisible);
        result = 31 * result + Boolean.hashCode(isMobileDataEnabled);
        result = 31 * result + Boolean.hashCode(isCdmaOptions);
        result = 31 * result + Boolean.hashCode(isGsmOptions);
        result = 31 * result + Boolean.hashCode(isWorldMode);
        result = 31 * result + Boolean.hashCode(shouldDisplayNetworkSelectOptions);
        result = 31 * result + Boolean.hashCode(isTdscdmaSupported);
        result = 31 * result + Boolean.hashCode(activeNetworkIsCellular);
        result = 31 * result + Boolean.hashCode(showToggleForPhysicalSim);
        result = 31 * result + Boolean.hashCode(isDataRoamingEnabled);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MobileNetworkInfoEntity)) {
            return false;
        }

        MobileNetworkInfoEntity info = (MobileNetworkInfoEntity) obj;
        return  TextUtils.equals(subId, info.subId)
                && isContactDiscoveryEnabled == info.isContactDiscoveryEnabled
                && isContactDiscoveryVisible == info.isContactDiscoveryVisible
                && isMobileDataEnabled == info.isMobileDataEnabled
                && isCdmaOptions == info.isCdmaOptions
                && isGsmOptions == info.isGsmOptions
                && isWorldMode == info.isWorldMode
                && shouldDisplayNetworkSelectOptions == info.shouldDisplayNetworkSelectOptions
                && isTdscdmaSupported == info.isTdscdmaSupported
                && activeNetworkIsCellular == info.activeNetworkIsCellular
                && showToggleForPhysicalSim == info.showToggleForPhysicalSim
                && isDataRoamingEnabled == info.isDataRoamingEnabled;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(" {MobileNetworkInfoEntity(subId = ")
                .append(subId)
                .append(", isContactDiscoveryEnabled = ")
                .append(isContactDiscoveryEnabled)
                .append(", isContactDiscoveryVisible = ")
                .append(isContactDiscoveryVisible)
                .append(", isMobileDataEnabled = ")
                .append(isMobileDataEnabled)
                .append(", isCdmaOptions = ")
                .append(isCdmaOptions)
                .append(", isGsmOptions = ")
                .append(isGsmOptions)
                .append(", isWorldMode = ")
                .append(isWorldMode)
                .append(", shouldDisplayNetworkSelectOptions = ")
                .append(shouldDisplayNetworkSelectOptions)
                .append(", isTdscdmaSupported = ")
                .append(isTdscdmaSupported)
                .append(", activeNetworkIsCellular = ")
                .append(activeNetworkIsCellular)
                .append(", showToggleForPhysicalSim = ")
                .append(showToggleForPhysicalSim)
                .append(", isDataRoamingEnabled = ")
                .append(isDataRoamingEnabled)
                .append(")}");
        return builder.toString();
    }
}
