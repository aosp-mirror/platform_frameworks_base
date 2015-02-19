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
     * @return a list of all subscriptions in the database, this includes
     * all subscriptions that have been seen.
     */
    List<SubscriptionInfo> getAllSubInfoList();

    /**
     * @return the count of all subscriptions in the database, this includes
     * all subscriptions that have been seen.
     */
    int getAllSubInfoCount();

    /**
     * Get the active SubscriptionInfo with the subId key
     * @param subId The unique SubscriptionInfo key in database
     * @return SubscriptionInfo, maybe null if its not active
     */
    SubscriptionInfo getActiveSubscriptionInfo(int subId);

    /**
     * Get the active SubscriptionInfo associated with the iccId
     * @param iccId the IccId of SIM card
     * @return SubscriptionInfo, maybe null if its not active
     */
    SubscriptionInfo getActiveSubscriptionInfoForIccId(String iccId);

    /**
     * Get the active SubscriptionInfo associated with the slotIdx
     * @param slotIdx the slot which the subscription is inserted
     * @return SubscriptionInfo, maybe null if its not active
     */
    SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIdx);

    /**
     * Get the SubscriptionInfo(s) of the active subscriptions. The records will be sorted
     * by {@link SubscriptionInfo#getSimSlotIndex} then by {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @return Sorted list of the currently {@link SubscriptionInfo} records available on the device.
     * <ul>
     * <li>
     * If null is returned the current state is unknown but if a {@link OnSubscriptionsChangedListener}
     * has been registered {@link OnSubscriptionsChangedListener#onSubscriptionsChanged} will be
     * invoked in the future.
     * </li>
     * <li>
     * If the list is empty then there are no {@link SubscriptionInfo} records currently available.
     * </li>
     * <li>
     * if the list is non-empty the list is sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     * </li>
     * </ul>
     */
    List<SubscriptionInfo> getActiveSubscriptionInfoList();

    /**
     * @return the number of active subscriptions
     */
    int getActiveSubInfoCount();

    /**
     * @return the maximum number of subscriptions this device will support at any one time.
     */
    int getActiveSubInfoCountMax();

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

    /**
     * Get the SIM state for the subscriber
     * @return SIM state as the ordinal of IccCardConstants.State
     */
    int getSimStateForSubscriber(int subId);

}
