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

import static androidx.room.ForeignKey.CASCADE;

import android.text.TextUtils;

import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = DataServiceUtils.SubscriptionInfoData.TABLE_NAME)
public class SubscriptionInfoEntity {
    public SubscriptionInfoEntity(@NonNull String subId, int simSlotIndex, int carrierId,
            String displayName, String carrierName, int dataRoaming, String mcc, String mnc,
            String countryIso, boolean isEmbedded, int cardId, int portIndex,
            boolean isOpportunistic, @Nullable String groupUUID, int subscriptionType,
            String uniqueName, boolean isSubscriptionVisible, String formattedPhoneNumber,
            boolean isFirstRemovableSubscription, boolean isDefaultSubscriptionSelection,
            boolean isValidSubscription, boolean isUsableSubscription,
            boolean isActiveSubscriptionId, boolean isAvailableSubscription,
            boolean isActiveDataSubscriptionId) {
        this.subId = subId;
        this.simSlotIndex = simSlotIndex;
        this.carrierId = carrierId;
        this.displayName = displayName;
        this.carrierName = carrierName;
        this.dataRoaming = dataRoaming;
        this.mcc = mcc;
        this.mnc = mnc;
        this.countryIso = countryIso;
        this.isEmbedded = isEmbedded;
        this.cardId = cardId;
        this.portIndex = portIndex;
        this.isOpportunistic = isOpportunistic;
        this.groupUUID = groupUUID;
        this.subscriptionType = subscriptionType;
        this.uniqueName = uniqueName;
        this.isSubscriptionVisible = isSubscriptionVisible;
        this.formattedPhoneNumber = formattedPhoneNumber;
        this.isFirstRemovableSubscription = isFirstRemovableSubscription;
        this.isDefaultSubscriptionSelection = isDefaultSubscriptionSelection;
        this.isValidSubscription = isValidSubscription;
        this.isUsableSubscription = isUsableSubscription;
        this.isActiveSubscriptionId = isActiveSubscriptionId;
        this.isAvailableSubscription = isAvailableSubscription;
        this.isActiveDataSubscriptionId = isActiveDataSubscriptionId;
    }

    @PrimaryKey
    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_ID, index = true)
    @NonNull
    public String subId;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_SIM_SLOT_INDEX)
    public int simSlotIndex;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_CARRIER_ID)
    public int carrierId;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_DISPLAY_NAME)
    public String displayName;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_CARRIER_NAME)
    public String carrierName;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_DATA_ROAMING)
    public int dataRoaming;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_MCC)
    public String mcc;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_MNC)
    public String mnc;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_COUNTRY_ISO)
    public String countryIso;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_EMBEDDED)
    public boolean isEmbedded;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_CARD_ID)
    public int cardId;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_PORT_INDEX)
    public int portIndex;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_OPPORTUNISTIC)
    public boolean isOpportunistic;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_GROUP_UUID)
    @Nullable
    public String groupUUID;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_SUBSCRIPTION_TYPE)
    public int subscriptionType;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_UNIQUE_NAME)
    public String uniqueName;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_SUBSCRIPTION_VISIBLE)
    public boolean isSubscriptionVisible;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_FORMATTED_PHONE_NUMBER)
    public String formattedPhoneNumber;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_FIRST_REMOVABLE_SUBSCRIPTION)
    public boolean isFirstRemovableSubscription;

    @ColumnInfo(name =
            DataServiceUtils.SubscriptionInfoData.COLUMN_IS_DEFAULT_SUBSCRIPTION_SELECTION)
    public boolean isDefaultSubscriptionSelection;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_VALID_SUBSCRIPTION)
    public boolean isValidSubscription;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_USABLE_SUBSCRIPTION)
    public boolean isUsableSubscription;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_ACTIVE_SUBSCRIPTION_ID)
    public boolean isActiveSubscriptionId;

    @ColumnInfo(name = DataServiceUtils.SubscriptionInfoData.COLUMN_IS_AVAILABLE_SUBSCRIPTION)
    public boolean isAvailableSubscription;

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
        int result = 17;
        result = 31 * result + subId.hashCode();
        result = 31 * result + simSlotIndex;
        result = 31 * result + carrierId;
        result = 31 * result + displayName.hashCode();
        result = 31 * result + carrierName.hashCode();
        result = 31 * result + dataRoaming;
        result = 31 * result + mcc.hashCode();
        result = 31 * result + mnc.hashCode();
        result = 31 * result + countryIso.hashCode();
        result = 31 * result + Boolean.hashCode(isEmbedded);
        result = 31 * result + cardId;
        result = 31 * result + portIndex;
        result = 31 * result + Boolean.hashCode(isOpportunistic);
        result = 31 * result + groupUUID.hashCode();
        result = 31 * result + subscriptionType;
        result = 31 * result + uniqueName.hashCode();
        result = 31 * result + Boolean.hashCode(isSubscriptionVisible);
        result = 31 * result + formattedPhoneNumber.hashCode();
        result = 31 * result + Boolean.hashCode(isFirstRemovableSubscription);
        result = 31 * result + Boolean.hashCode(isDefaultSubscriptionSelection);
        result = 31 * result + Boolean.hashCode(isValidSubscription);
        result = 31 * result + Boolean.hashCode(isUsableSubscription);
        result = 31 * result + Boolean.hashCode(isActiveSubscriptionId);
        result = 31 * result + Boolean.hashCode(isAvailableSubscription);
        result = 31 * result + Boolean.hashCode(isActiveDataSubscriptionId);
        return result;
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
                && carrierId == info.carrierId
                && TextUtils.equals(displayName, info.displayName)
                && TextUtils.equals(carrierName, info.carrierName)
                && dataRoaming == info.dataRoaming
                && TextUtils.equals(mcc, info.mcc)
                && TextUtils.equals(mnc, info.mnc)
                && TextUtils.equals(countryIso, info.countryIso)
                && isEmbedded == info.isEmbedded
                && cardId == info.cardId
                && portIndex == info.portIndex
                && isOpportunistic == info.isOpportunistic
                && TextUtils.equals(groupUUID, info.groupUUID)
                && subscriptionType == info.subscriptionType
                && TextUtils.equals(uniqueName, info.uniqueName)
                && isSubscriptionVisible == info.isSubscriptionVisible
                && TextUtils.equals(formattedPhoneNumber, info.formattedPhoneNumber)
                && isFirstRemovableSubscription == info.isFirstRemovableSubscription
                && isDefaultSubscriptionSelection == info.isDefaultSubscriptionSelection
                && isValidSubscription == info.isValidSubscription
                && isUsableSubscription == info.isUsableSubscription
                && isActiveSubscriptionId == info.isActiveSubscriptionId
                && isAvailableSubscription == info.isAvailableSubscription
                && isActiveDataSubscriptionId == info.isActiveDataSubscriptionId;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(" {SubscriptionInfoEntity(subId = ")
                .append(subId)
                .append(", simSlotIndex = ")
                .append(simSlotIndex)
                .append(", carrierId = ")
                .append(carrierId)
                .append(", displayName = ")
                .append(displayName)
                .append(", carrierName = ")
                .append(carrierName)
                .append(", dataRoaming = ")
                .append(dataRoaming)
                .append(", mcc = ")
                .append(mcc)
                .append(", mnc = ")
                .append(mnc)
                .append(", countryIso = ")
                .append(countryIso)
                .append(", isEmbedded = ")
                .append(isEmbedded)
                .append(", cardId = ")
                .append(cardId)
                .append(", portIndex = ")
                .append(portIndex)
                .append(", isOpportunistic = ")
                .append(isOpportunistic)
                .append(", groupUUID = ")
                .append(groupUUID)
                .append(", subscriptionType = ")
                .append(subscriptionType)
                .append(", uniqueName = ")
                .append(uniqueName)
                .append(", isSubscriptionVisible = ")
                .append(isSubscriptionVisible)
                .append(", formattedPhoneNumber = ")
                .append(formattedPhoneNumber)
                .append(", isFirstRemovableSubscription = ")
                .append(isFirstRemovableSubscription)
                .append(", isDefaultSubscriptionSelection = ")
                .append(isDefaultSubscriptionSelection)
                .append(", isValidSubscription = ")
                .append(isValidSubscription)
                .append(", isUsableSubscription = ")
                .append(isUsableSubscription)
                .append(", isActiveSubscriptionId = ")
                .append(isActiveSubscriptionId)
                .append(", isAvailableSubscription = ")
                .append(isAvailableSubscription)
                .append(", isActiveDataSubscriptionId = ")
                .append(isActiveDataSubscriptionId)
                .append(")}");
        return builder.toString();
    }
}
