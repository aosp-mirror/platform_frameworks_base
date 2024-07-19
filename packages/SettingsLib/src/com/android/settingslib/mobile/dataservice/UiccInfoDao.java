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

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface UiccInfoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertUiccInfo(UiccInfoEntity... uiccInfo);

    @Query("SELECT * FROM " + DataServiceUtils.UiccInfoData.TABLE_NAME + " ORDER BY "
            + DataServiceUtils.UiccInfoData.COLUMN_ID)
    LiveData<List<UiccInfoEntity>> queryAllUiccInfos();

    @Query("SELECT COUNT(*) FROM " + DataServiceUtils.UiccInfoData.TABLE_NAME)
    int count();

    @Query("DELETE FROM " + DataServiceUtils.UiccInfoData.TABLE_NAME + " WHERE "
            + DataServiceUtils.UiccInfoData.COLUMN_ID + " = :id")
    void deleteBySubId(String id);
}
