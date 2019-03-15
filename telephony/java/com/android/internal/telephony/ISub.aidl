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

import android.telephony.SubscriptionInfo;
import com.android.internal.telephony.ISetOpportunisticDataCallback;

interface ISub {
    /**
     * @param callingPackage The package maing the call.
     * @return a list of all subscriptions in the database, this includes
     * all subscriptions that have been seen.
     */
    List<SubscriptionInfo> getAllSubInfoList(String callingPackage);

    /**
     * @param callingPackage The package maing the call.
     * @return the count of all subscriptions in the database, this includes
     * all subscriptions that have been seen.
     */
    int getAllSubInfoCount(String callingPackage);

    /**
     * Get the active SubscriptionInfo with the subId key
     * @param subId The unique SubscriptionInfo key in database
     * @param callingPackage The package maing the call.
     * @return SubscriptionInfo, maybe null if its not active
     */
    SubscriptionInfo getActiveSubscriptionInfo(int subId, String callingPackage);

    /**
     * Get the active SubscriptionInfo associated with the iccId
     * @param iccId the IccId of SIM card
     * @param callingPackage The package maing the call.
     * @return SubscriptionInfo, maybe null if its not active
     */
    SubscriptionInfo getActiveSubscriptionInfoForIccId(String iccId, String callingPackage);

    /**
     * Get the active SubscriptionInfo associated with the slotIndex
     * @param slotIndex the slot which the subscription is inserted
     * @param callingPackage The package making the call.
     * @return SubscriptionInfo, null for Remote-SIMs or non-active slotIndex.
     */
    SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIndex, String callingPackage);

    /**
     * Get the SubscriptionInfo(s) of the active subscriptions. The records will be sorted
     * by {@link SubscriptionInfo#getSimSlotIndex} then by {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @param callingPackage The package maing the call.
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
    List<SubscriptionInfo> getActiveSubscriptionInfoList(String callingPackage);

    /**
     * @param callingPackage The package making the call.
     * @return the number of active subscriptions
     */
    int getActiveSubInfoCount(String callingPackage);

    /**
     * @return the maximum number of subscriptions this device will support at any one time.
     */
    int getActiveSubInfoCountMax();

    /**
     * @see android.telephony.SubscriptionManager#getAvailableSubscriptionInfoList
     */
    List<SubscriptionInfo> getAvailableSubscriptionInfoList(String callingPackage);

    /**
     * @see android.telephony.SubscriptionManager#getAccessibleSubscriptionInfoList
     */
    List<SubscriptionInfo> getAccessibleSubscriptionInfoList(String callingPackage);

    /**
     * @see android.telephony.SubscriptionManager#requestEmbeddedSubscriptionInfoListRefresh
     */
    oneway void requestEmbeddedSubscriptionInfoListRefresh(int cardId);

    /**
     * Add a new SubscriptionInfo to subinfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotIndex the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     */
    int addSubInfoRecord(String iccId, int slotIndex);

    /**
     * Add a new subscription info record, if needed
     * @param uniqueId This is the unique identifier for the subscription within the specific
     *                 subscription type.
     * @param displayName human-readable name of the device the subscription corresponds to.
     * @param slotIndex the slot assigned to this device
     * @param subscriptionType the type of subscription to be added.
     * @return 0 if success, < 0 on error.
     */
    int addSubInfo(String uniqueId, String displayName, int slotIndex, int subscriptionType);

    /**
     * Remove subscription info record for the given device.
     * @param uniqueId This is the unique identifier for the subscription within the specific
     *                      subscription type.
     * @param subscriptionType the type of subscription to be removed
     * @return 0 if success, < 0 on error.
     */
    int removeSubInfo(String uniqueId, int subscriptionType);

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

    /**
     * Switch to a certain subscription
     *
     * @param opportunistic whether it’s opportunistic subscription.
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     */
    int setOpportunistic(boolean opportunistic, int subId, String callingPackage);

    /**
     * Inform SubscriptionManager that subscriptions in the list are bundled
     * as a group. Typically it's a primary subscription and an opportunistic
     * subscription. It should only affect multi-SIM scenarios where primary
     * and opportunistic subscriptions can be activated together.
     * Being in the same group means they might be activated or deactivated
     * together, some of them may be invisible to the users, etc.
     *
     * Caller will either have {@link android.Manifest.permission.MODIFY_PHONE_STATE}
     * permission or can manage all subscriptions in the list, according to their
     * acess rules.
     *
     * @param subIdList list of subId that will be in the same group
     * @return groupUUID a UUID assigned to the subscription group. It returns
     * null if fails.
     *
     */
    String setSubscriptionGroup(in int[] subIdList, String callingPackage);

    /**
     * Set which subscription is preferred for cellular data. It's
     * designed to overwrite default data subscription temporarily.
     *
     * @param subId which subscription is preferred to for cellular data.
     * @param needValidation whether validation is needed before switching.
     * @param callback callback upon request completion.
     *
     * @hide
     *
     */
    void setPreferredDataSubscriptionId(int subId, boolean needValidation,
                     ISetOpportunisticDataCallback callback);

    /**
     * Get which subscription is preferred for cellular data.
     *
     * @hide
     *
     */
    int getPreferredDataSubscriptionId();

    /**
     * Get User downloaded Profiles.
     *
     * Return opportunistic subscriptions that can be visible to the caller.
     * @return the list of opportunistic subscription info. If none exists, an empty list.
     */
    List<SubscriptionInfo> getOpportunisticSubscriptions(String callingPackage);

    boolean removeSubscriptionsFromGroup(in int[] subIdList, String callingPackage);

    List<SubscriptionInfo> getSubscriptionsInGroup(int subId, String callingPackage);

    int getSlotIndex(int subId);

    int[] getSubId(int slotIndex);

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

    int[] getActiveSubIdList(boolean visibleOnly);

    int setSubscriptionProperty(int subId, String propKey, String propValue);

    String getSubscriptionProperty(int subId, String propKey, String callingPackage);

    boolean setSubscriptionEnabled(boolean enable, int subId);

    boolean isSubscriptionEnabled(int subId);

    int getEnabledSubscriptionId(int slotIndex);
    /**
     * Get the SIM state for the slot index
     * @return SIM state as the ordinal of IccCardConstants.State
     */
    int getSimStateForSlotIndex(int slotIndex);

    boolean isActiveSubId(int subId, String callingPackage);
}
