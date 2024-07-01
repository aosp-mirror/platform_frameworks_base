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

    public MobileNetworkInfoEntity(@NonNull String subId, boolean isMobileDataEnabled,
            boolean showToggleForPhysicalSim) {
        this.subId = subId;
        this.isMobileDataEnabled = isMobileDataEnabled;
        this.showToggleForPhysicalSim = showToggleForPhysicalSim;
    }

    @PrimaryKey
    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_ID, index = true)
    @NonNull
    public String subId;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_IS_MOBILE_DATA_ENABLED)
    public boolean isMobileDataEnabled;

    @ColumnInfo(name = DataServiceUtils.MobileNetworkInfoData.COLUMN_SHOW_TOGGLE_FOR_PHYSICAL_SIM)
    public boolean showToggleForPhysicalSim;

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + subId.hashCode();
        result = 31 * result + Boolean.hashCode(isMobileDataEnabled);
        result = 31 * result + Boolean.hashCode(showToggleForPhysicalSim);
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
                && isMobileDataEnabled == info.isMobileDataEnabled
                && showToggleForPhysicalSim == info.showToggleForPhysicalSim;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(" {MobileNetworkInfoEntity(subId = ")
                .append(subId)
                .append(", isMobileDataEnabled = ")
                .append(isMobileDataEnabled)
                .append(", activeNetworkIsCellular = ")
                .append(showToggleForPhysicalSim)
                .append(")}");
        return builder.toString();
    }
}
