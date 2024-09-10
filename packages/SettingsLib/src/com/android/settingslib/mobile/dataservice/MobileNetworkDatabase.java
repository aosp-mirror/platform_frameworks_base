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

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.List;
import java.util.Objects;

@Database(entities = {SubscriptionInfoEntity.class, UiccInfoEntity.class,
        MobileNetworkInfoEntity.class}, exportSchema = false, version = 1)
public abstract class MobileNetworkDatabase extends RoomDatabase {

    public static final String TAG = "MobileNetworkDatabase";

    public abstract SubscriptionInfoDao mSubscriptionInfoDao();

    public abstract UiccInfoDao mUiccInfoDao();

    public abstract MobileNetworkInfoDao mMobileNetworkInfoDao();

    private static MobileNetworkDatabase sInstance;
    private static final Object sLOCK = new Object();


    /**
     * Create the MobileNetworkDatabase.
     *
     * @param context The context.
     * @return The MobileNetworkDatabase.
     */
    public static MobileNetworkDatabase getInstance(Context context) {
        synchronized (sLOCK) {
            if (Objects.isNull(sInstance)) {
                Log.d(TAG, "createDatabase.");
                sInstance = Room.inMemoryDatabaseBuilder(context, MobileNetworkDatabase.class)
                        .fallbackToDestructiveMigration()
                        .enableMultiInstanceInvalidation()
                        .build();
            }
        }
        return sInstance;
    }

    /**
     * Insert the subscription info to the SubscriptionInfoEntity table.
     *
     * @param subscriptionInfo The subscriptionInfo.
     */
    public void insertSubsInfo(SubscriptionInfoEntity... subscriptionInfo) {
        Log.d(TAG, "insertSubInfo");
        mSubscriptionInfoDao().insertSubsInfo(subscriptionInfo);
    }

    /**
     * Insert the UICC info to the UiccInfoEntity table.
     *
     * @param uiccInfoEntity The uiccInfoEntity.
     */
    public void insertUiccInfo(UiccInfoEntity... uiccInfoEntity) {
        Log.d(TAG, "insertUiccInfo");
        mUiccInfoDao().insertUiccInfo(uiccInfoEntity);
    }

    /**
     * Insert the mobileNetwork info to the MobileNetworkInfoEntity table.
     *
     * @param mobileNetworkInfoEntity The mobileNetworkInfoEntity.
     */
    public void insertMobileNetworkInfo(MobileNetworkInfoEntity... mobileNetworkInfoEntity) {
        Log.d(TAG, "insertMobileNetworkInfo");
        mMobileNetworkInfoDao().insertMobileNetworkInfo(mobileNetworkInfoEntity);
    }

    /**
     * Query available subscription infos from the SubscriptionInfoEntity table.
     */
    public LiveData<List<SubscriptionInfoEntity>> queryAvailableSubInfos() {
        return mSubscriptionInfoDao().queryAvailableSubInfos();
    }

    /**
     * Query the subscription info by the subscription ID from the SubscriptionInfoEntity
     * table.
     */
    public SubscriptionInfoEntity querySubInfoById(String id) {
        return mSubscriptionInfoDao().querySubInfoById(id);
    }

    /**
     * Query all mobileNetwork infos from the MobileNetworkInfoEntity
     * table.
     */
    public LiveData<List<MobileNetworkInfoEntity>> queryAllMobileNetworkInfo() {
        return mMobileNetworkInfoDao().queryAllMobileNetworkInfos();
    }

    /**
     * Query the mobileNetwork info by the subscription ID from the MobileNetworkInfoEntity
     * table.
     */
    public MobileNetworkInfoEntity queryMobileNetworkInfoById(String id) {
        return mMobileNetworkInfoDao().queryMobileNetworkInfoBySubId(id);
    }

    /**
     * Query all UICC infos from the UiccInfoEntity table.
     */
    public LiveData<List<UiccInfoEntity>> queryAllUiccInfo() {
        return mUiccInfoDao().queryAllUiccInfos();
    }

    /**
     * Delete the subscriptionInfo info by the subscription ID from the SubscriptionInfoEntity
     * table.
     */
    public void deleteSubInfoBySubId(String id) {
        mSubscriptionInfoDao().deleteBySubId(id);
    }

    /**
     * Delete the mobileNetwork info by the subscription ID from the MobileNetworkInfoEntity
     * table.
     */
    public void deleteMobileNetworkInfoBySubId(String id) {
        mMobileNetworkInfoDao().deleteBySubId(id);
    }

    /**
     * Delete the UICC info by the subscription ID from the UiccInfoEntity table.
     */
    public void deleteUiccInfoBySubId(String id) {
        mUiccInfoDao().deleteBySubId(id);
    }
}
