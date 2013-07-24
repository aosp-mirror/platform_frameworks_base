/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.accounts.Account;
import android.content.SyncInfo;
import android.content.ISyncStatusObserver;
import android.content.SyncAdapterType;
import android.content.SyncRequest;
import android.content.SyncStatusInfo;
import android.content.PeriodicSync;
import android.net.Uri;
import android.os.Bundle;
import android.database.IContentObserver;

/**
 * @hide
 */
interface IContentService {
    void unregisterContentObserver(IContentObserver observer);

    /**
     * Register a content observer tied to a specific user's view of the provider.
     * @param userHandle the user whose view of the provider is to be observed.  May be
     *     the calling user without requiring any permission, otherwise the caller needs to
     *     hold the INTERACT_ACROSS_USERS_FULL permission.  Pseudousers USER_ALL and
     *     USER_CURRENT are properly handled.
     */
    void registerContentObserver(in Uri uri, boolean notifyForDescendants,
            IContentObserver observer, int userHandle);

    /**
     * Notify observers of a particular user's view of the provider.
     * @param userHandle the user whose view of the provider is to be notified.  May be
     *     the calling user without requiring any permission, otherwise the caller needs to
     *     hold the INTERACT_ACROSS_USERS_FULL permission.  Pseudousers USER_ALL
     *     USER_CURRENT are properly interpreted.
     */
    void notifyChange(in Uri uri, IContentObserver observer,
            boolean observerWantsSelfNotifications, boolean syncToNetwork,
            int userHandle);

    void requestSync(in Account account, String authority, in Bundle extras);
    void sync(in SyncRequest request);
    void cancelSync(in Account account, String authority);

    /**
     * Check if the provider should be synced when a network tickle is received
     * @param providerName the provider whose setting we are querying
     * @return true if the provider should be synced when a network tickle is received
     */
    boolean getSyncAutomatically(in Account account, String providerName);

    /**
     * Set whether or not the provider is synced when it receives a network tickle.
     *
     * @param providerName the provider whose behavior is being controlled
     * @param sync true if the provider should be synced when tickles are received for it
     */
    void setSyncAutomatically(in Account account, String providerName, boolean sync);

    /**
     * Get the frequency of the periodic poll, if any.
     * @param providerName the provider whose setting we are querying
     * @return the frequency of the periodic sync in seconds. If 0 then no periodic syncs
     * will take place.
     */
    List<PeriodicSync> getPeriodicSyncs(in Account account, String providerName);

    /**
     * Set whether or not the provider is to be synced on a periodic basis.
     *
     * @param providerName the provider whose behavior is being controlled
     * @param pollFrequency the period that a sync should be performed, in seconds. If this is
     * zero or less then no periodic syncs will be performed.
     */
    void addPeriodicSync(in Account account, String providerName, in Bundle extras,
      long pollFrequency);

    /**
     * Set whether or not the provider is to be synced on a periodic basis.
     *
     * @param providerName the provider whose behavior is being controlled
     * @param pollFrequency the period that a sync should be performed, in seconds. If this is
     * zero or less then no periodic syncs will be performed.
     */
    void removePeriodicSync(in Account account, String providerName, in Bundle extras);

    /**
     * Check if this account/provider is syncable.
     * @return >0 if it is syncable, 0 if not, and <0 if the state isn't known yet.
     */
    int getIsSyncable(in Account account, String providerName);

    /**
     * Set whether this account/provider is syncable.
     * @param syncable, >0 denotes syncable, 0 means not syncable, <0 means unknown
     */
    void setIsSyncable(in Account account, String providerName, int syncable);

    void setMasterSyncAutomatically(boolean flag);

    boolean getMasterSyncAutomatically();

    /**
     * Returns true if there is currently a sync operation for the given
     * account or authority in the pending list, or actively being processed.
     */
    boolean isSyncActive(in Account account, String authority);

    List<SyncInfo> getCurrentSyncs();

    /**
     * Returns the types of the SyncAdapters that are registered with the system.
     * @return Returns the types of the SyncAdapters that are registered with the system.
     */
    SyncAdapterType[] getSyncAdapterTypes();

    /**
     * Returns the status that matches the authority. If there are multiples accounts for
     * the authority, the one with the latest "lastSuccessTime" status is returned.
     * @param authority the authority whose row should be selected
     * @return the SyncStatusInfo for the authority, or null if none exists
     */
    SyncStatusInfo getSyncStatus(in Account account, String authority);

    /**
     * Return true if the pending status is true of any matching authorities.
     */
    boolean isSyncPending(in Account account, String authority);

    void addStatusChangeListener(int mask, ISyncStatusObserver callback);

    void removeStatusChangeListener(ISyncStatusObserver callback);
}
