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

    public UiccInfoEntity(@NonNull String subId, boolean isActive) {
        this.subId = subId;
        this.isActive = isActive;
    }

    @PrimaryKey
    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_ID, index = true)
    @NonNull
    public String subId;

    @ColumnInfo(name = DataServiceUtils.UiccInfoData.COLUMN_IS_ACTIVE)
    public boolean isActive;

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + subId.hashCode();
        result = 31 * result + Boolean.hashCode(isActive);
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
        return TextUtils.equals(subId, info.subId) && isActive == info.isActive;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(" {UiccInfoEntity(subId = ")
                .append(subId)
                .append(", isActive = ")
                .append(isActive)
                .append(")}");
        return builder.toString();
    }
}
