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

import java.util.List;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface SubscriptionInfoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSubsInfo(SubscriptionInfoEntity... subscriptionInfo);

    @Query("SELECT * FROM " + DataServiceUtils.SubscriptionInfoData.TABLE_NAME + " ORDER BY "
            + DataServiceUtils.SubscriptionInfoData.COLUMN_ID)
    LiveData<List<SubscriptionInfoEntity>> queryAvailableSubInfos();

    @Query("SELECT * FROM " + DataServiceUtils.SubscriptionInfoData.TABLE_NAME + " WHERE "
            + DataServiceUtils.SubscriptionInfoData.COLUMN_ID + " = :subId")
    LiveData<SubscriptionInfoEntity> querySubInfoById(String subId);

    @Query("SELECT * FROM " + DataServiceUtils.SubscriptionInfoData.TABLE_NAME + " WHERE "
            + DataServiceUtils.SubscriptionInfoData.COLUMN_IS_ACTIVE_SUBSCRIPTION_ID
            + " = :isActiveSubscription" + " AND "
            + DataServiceUtils.SubscriptionInfoData.COLUMN_IS_SUBSCRIPTION_VISIBLE
            + " = :isSubscriptionVisible")
    LiveData<List<SubscriptionInfoEntity>> queryActiveSubInfos(
            boolean isActiveSubscription, boolean isSubscriptionVisible);

    @Query("SELECT COUNT(*) FROM " + DataServiceUtils.SubscriptionInfoData.TABLE_NAME)
    int count();

    @Query("DELETE FROM " + DataServiceUtils.SubscriptionInfoData.TABLE_NAME + " WHERE "
            + DataServiceUtils.SubscriptionInfoData.COLUMN_ID + " = :id")
    void deleteBySubId(String id);

}
