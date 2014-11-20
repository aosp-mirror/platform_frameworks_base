/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telephony;

import android.app.PendingIntent;
import android.telephony.SubscriptionInfo;
import com.android.internal.telephony.ISubscriptionListener;

interface ISub {
    /**
     * Get the SubscriptionInfo according to an index
     * @param subId The unique SubscriptionInfo index in database
     * @return SubscriptionInfo, maybe null
     */
    SubscriptionInfo getSubInfoForSubscriber(int subId);

    /**
     * Get the SubscriptionInfo according to an IccId
     * @param iccId the IccId of SIM card
     * @return SubscriptionInfo, maybe null
     */
    List<SubscriptionInfo> getSubInfoUsingIccId(String iccId);

    /**
     * Get the SubscriptionInfo according to slotId
     * @param slotId the slot which the SIM is inserted
     * @return SubscriptionInfo, maybe null
     */
    List<SubscriptionInfo> getSubInfoUsingSlotId(int slotId);

    /**
     * Get all the SubscriptionInfo(s) in subinfo database
     * @return Array list of all SubInfoRecords in database, include thsoe that were inserted before
     */
    List<SubscriptionInfo> getAllSubInfoList();

    /**
     * Get the SubscriptionInfo(s) of the currently inserted SIM(s)
     * @return Array list of currently inserted SubscriptionInfo(s)
     */
    List<SubscriptionInfo> getActiveSubInfoList();

    /**
     * Get the SUB count of all SUB(s) in subinfo database
     * @return all SIM count in database, include what was inserted before
     */
    int getAllSubInfoCount();

    /**
     * Get the count of active SUB(s)
     * @return active SIM count
     */
    int getActiveSubInfoCount();

    /**
     * Add a new SubscriptionInfo to subinfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotId the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     */
    int addSubInfoRecord(String iccId, int slotId);

    /**
     * Set SIM icon tint color by simInfo index
     * @param tint the icon tint color of the SIM
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     */
    int setIconTint(int tint, int subId);

    /**
     * Set display name by simInfo index
     * @param displayName the display name of SIM card
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     */
    int setDisplayName(String displayName, int subId);

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubscriptionInfo index in database
     * @param nameSource, 0: DEFAULT_SOURCE, 1: SIM_SOURCE, 2: USER_INPUT
     * @return the number of records updated
     */
    int setDisplayNameUsingSrc(String displayName, int subId, long nameSource);

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     */
    int setDisplayNumber(String number, int subId);

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     */
    int setDataRoaming(int roaming, int subId);

    int getSlotId(int subId);

    int[] getSubId(int slotId);

    int getDefaultSubId();

    int clearSubInfo();

    int getPhoneId(int subId);

    /**
     * Get the default data subscription
     * @return Id of the data subscription
     */
    int getDefaultDataSubId();

    void setDefaultDataSubId(int subId);

    int getDefaultVoiceSubId();

    void setDefaultVoiceSubId(int subId);

    int getDefaultSmsSubId();

    void setDefaultSmsSubId(int subId);

    void clearDefaultsForInactiveSubIds();

    int[] getActiveSubIdList();
}
