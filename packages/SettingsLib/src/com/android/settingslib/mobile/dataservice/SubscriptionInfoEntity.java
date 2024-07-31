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

import java.util.Objects;

@Entity(tableName = DataServiceUtils.SubscriptionInfoData.TABLE_NAME)
public class SubscriptionInfoEntity {
    public SubscriptionInfoEntity(@NonNull String subId, int simSlotIndex, boolean isEmbedded,
            boolean isOpportunistic, String uniqueName, boolean isSubscriptionVisible,
            boolean isDefaultSubscriptionSelection, boolean isValidSubscription,
            boolean isActiveSubscriptionId, boolean isActiveDataSubscriptionId) {
        this.subId = subId;
        this.simSlotIndex = simSlotIndex;
        this.isEmbedded = isEmbedded;
        this.isOpportunistic = isOpportunistic;
        this.uniqueName = uniqueName;
        this.isSubscriptionVisible = isSubscriptionVisible;
        this.isDefaultSubscriptionSelection = isDefaultSubscriptionSelection;
        this.isValidSubscription = isValidSubscription;
        this.isActiveSubscriptionId = isActiveSubscriptionId;
        this.isActiveDataSubscriptionId = isActiveDataSubscriptionId;
    }

    @PrimaryKey
    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_ID, index = true)
    @NonNull
    public String subId;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_SIM_SLOT_INDEX)
    public int simSlotIndex;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_EMBEDDED)
    public boolean isEmbedded;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_OPPORTUNISTIC)
    public boolean isOpportunistic;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_UNIQUE_NAME)
    public String uniqueName;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_SUBSCRIPTION_VISIBLE)
    public boolean isSubscriptionVisible;

    @ColumnInfo(name =
            DataServiceUtils.SubscriptionInfoData.COLUMN_IS_DEFAULT_SUBSCRIPTION_SELECTION)
    public boolean isDefaultSubscriptionSelection;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_VALID_SUBSCRIPTION)
    public boolean isValidSubscription;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_ACTIVE_SUBSCRIPTION_ID)
    public boolean isActiveSubscriptionId;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_ACTIVE_DATA_SUBSCRIPTION)
    public boolean isActiveDataSubscriptionId;

    public int getSubId() {
        return Integer.valueOf(subId);
    }

    public CharSequence getUniqueDisplayName() {
        return uniqueName;
    }

    public boolean isActiveSubscription() {
        return isActiveSubscriptionId;
    }

    public boolean isSubscriptionVisible() {
        return isSubscriptionVisible;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                subId,
                simSlotIndex,
                isEmbedded,
                isOpportunistic,
                uniqueName,
                isSubscriptionVisible,
                isDefaultSubscriptionSelection,
                isValidSubscription,
                isActiveSubscriptionId,
                isActiveDataSubscriptionId);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SubscriptionInfoEntity)) {
            return false;
        }

        SubscriptionInfoEntity info = (SubscriptionInfoEntity) obj;
        return  TextUtils.equals(subId, info.subId)
                && simSlotIndex == info.simSlotIndex
                && isEmbedded == info.isEmbedded
                && isOpportunistic == info.isOpportunistic
                && TextUtils.equals(uniqueName, info.uniqueName)
                && isSubscriptionVisible == info.isSubscriptionVisible
                && isDefaultSubscriptionSelection == info.isDefaultSubscriptionSelection
                && isValidSubscription == info.isValidSubscription
                && isActiveSubscriptionId == info.isActiveSubscriptionId
                && isActiveDataSubscriptionId == info.isActiveDataSubscriptionId;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(" {SubscriptionInfoEntity(subId = ")
                .append(subId)
                .append(", simSlotIndex = ")
                .append(simSlotIndex)
                .append(", isEmbedded = ")
                .append(isEmbedded)
                .append(", isOpportunistic = ")
                .append(isOpportunistic)
                .append(", uniqueName = ")
                .append(uniqueName)
                .append(", isSubscriptionVisible = ")
                .append(isSubscriptionVisible)
                .append(", isDefaultSubscriptionSelection = ")
                .append(isDefaultSubscriptionSelection)
                .append(", isValidSubscription = ")
                .append(isValidSubscription)
                .append(", isActiveSubscriptionId = ")
                .append(isActiveSubscriptionId)
                .append(", isActiveDataSubscriptionId = ")
                .append(isActiveDataSubscriptionId)
                .append(")}");
        return builder.toString();
    }
}
