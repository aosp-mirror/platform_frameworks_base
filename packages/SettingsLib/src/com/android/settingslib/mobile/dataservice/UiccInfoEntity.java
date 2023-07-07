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

@Entity(tableName = DataServiceUtils.UiccInfoData.TABLE_NAME)
public class UiccInfoEntity {

    public UiccInfoEntity(@NonNull String subId, @NonNull String physicalSlotIndex,
            int logicalSlotIndex, int cardId, boolean isEuicc,
            boolean isMultipleEnabledProfilesSupported, int cardState, boolean isRemovable,
            boolean isActive, int portIndex) {
        this.subId = subId;
        this.physicalSlotIndex = physicalSlotIndex;
        this.logicalSlotIndex = logicalSlotIndex;
        this.cardId = cardId;
        this.isEuicc = isEuicc;
        this.isMultipleEnabledProfilesSupported = isMultipleEnabledProfilesSupported;
        this.cardState = cardState;
        this.isRemovable = isRemovable;
        this.isActive = isActive;
        this.portIndex = portIndex;
    }

    @PrimaryKey
    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_ID, index = true)
    @NonNull
    public String subId;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_PHYSICAL_SLOT_INDEX)
    @NonNull
    public String physicalSlotIndex;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_LOGICAL_SLOT_INDEX)
    public int logicalSlotIndex;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_CARD_ID)
    public int cardId;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_IS_EUICC)
    public boolean isEuicc;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_IS_MULTIPLE_ENABLED_PROFILES_SUPPORTED)
    public boolean isMultipleEnabledProfilesSupported;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_CARD_STATE)
    public int cardState;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_IS_REMOVABLE)
    public boolean isRemovable;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_IS_ACTIVE)
    public boolean isActive;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_PORT_INDEX)
    public int portIndex;


    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + subId.hashCode();
        result = 31 * result + physicalSlotIndex.hashCode();
        result = 31 * result + logicalSlotIndex;
        result = 31 * result + cardId;
        result = 31 * result + Boolean.hashCode(isEuicc);
        result = 31 * result + Boolean.hashCode(isMultipleEnabledProfilesSupported);
        result = 31 * result + cardState;
        result = 31 * result + Boolean.hashCode(isRemovable);
        result = 31 * result + Boolean.hashCode(isActive);
        result = 31 * result + portIndex;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof UiccInfoEntity)) {
            return false;
        }

        UiccInfoEntity info = (UiccInfoEntity) obj;
        return  TextUtils.equals(subId, info.subId)
                && TextUtils.equals(physicalSlotIndex, info.physicalSlotIndex)
                && logicalSlotIndex == info.logicalSlotIndex
                && cardId == info.cardId
                && isEuicc == info.isEuicc
                && isMultipleEnabledProfilesSupported == info.isMultipleEnabledProfilesSupported
                && cardState == info.cardState
                && isRemovable == info.isRemovable
                && isActive == info.isActive
                && portIndex == info.portIndex;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(" {UiccInfoEntity(subId = ")
                .append(subId)
                .append(", logicalSlotIndex = ")
                .append(physicalSlotIndex)
                .append(", logicalSlotIndex = ")
                .append(logicalSlotIndex)
                .append(", cardId = ")
                .append(cardId)
                .append(", isEuicc = ")
                .append(isEuicc)
                .append(", isMultipleEnabledProfilesSupported = ")
                .append(isMultipleEnabledProfilesSupported)
                .append(", cardState = ")
                .append(cardState)
                .append(", isRemovable = ")
                .append(isRemovable)
                .append(", isActive = ")
                .append(isActive)
                .append(", portIndex = ")
                .append(portIndex)
                .append(")}");
        return builder.toString();
    }
}
