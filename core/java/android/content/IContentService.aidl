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
import android.content.ComponentName;
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
            IContentObserver observer, int userHandle, int targetSdkVersion);

    /**
     * Notify observers of a particular user's view of the provider.
     * @param userHandle the user whose view of the provider is to be notified.  May be
     *     the calling user without requiring any permission, otherwise the caller needs to
     *     hold the INTERACT_ACROSS_USERS_FULL permission.  Pseudousers USER_ALL
     *     USER_CURRENT are properly interpreted.
     */
    void notifyChange(in Uri uri, IContentObserver observer,
            boolean observerWantsSelfNotifications, int flags,
            int userHandle, int targetSdkVersion);

    void requestSync(in Account account, String authority, in Bundle extras);
    /**
     * Start a sync given a request.
     */
    void sync(in SyncRequest request);
    void syncAsUser(in SyncRequest request, int userId);
    @UnsupportedAppUsage
    void cancelSync(in Account account, String authority, in ComponentName cname);
    void cancelSyncAsUser(in Account account, String authority, in ComponentName cname, int userId);

    /** Cancel a sync, providing information about the sync to be cancelled. */
     void cancelRequest(in SyncRequest request);

    /**
     * Check if the provider should be synced when a network tickle is received
     * @param providerName the provider whose setting we are querying
     * @return true if the provider should be synced when a network tickle is received
     */
    boolean getSyncAutomatically(in Account account, String providerName);
    boolean getSyncAutomaticallyAsUser(in Account account, String providerName, int userId);

    /**
     * Set whether or not the provider is synced when it receives a network tickle.
     *
     * @param providerName the provider whose behavior is being controlled
     * @param sync true if the provider should be synced when tickles are received for it
     */
    void setSyncAutomatically(in Account account, String providerName, boolean sync);
    void setSyncAutomaticallyAsUser(in Account account, String providerName, boolean sync,
            int userId);

    /**
     * Get a list of periodic operations for a specified authority, or service.
     * @param account account for authority, must be null if cname is non-null.
     * @param providerName name of provider, must be null if cname is non-null.
     * @param cname component to identify sync service, must be null if account/providerName are
     * non-null.
     */
    List<PeriodicSync> getPeriodicSyncs(in Account account, String providerName,
        in ComponentName cname);

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
    @UnsupportedAppUsage
    int getIsSyncable(in Account account, String providerName);
    int getIsSyncableAsUser(in Account account, String providerName, int userId);

    /**
     * Set whether this account/provider is syncable.
     * @param syncable, >0 denotes syncable, 0 means not syncable, <0 means unknown
     */
    void setIsSyncable(in Account account, String providerName, int syncable);

    @UnsupportedAppUsage
    void setMasterSyncAutomatically(boolean flag);
    void setMasterSyncAutomaticallyAsUser(boolean flag, int userId);

    @UnsupportedAppUsage
    boolean getMasterSyncAutomatically();
    boolean getMasterSyncAutomaticallyAsUser(int userId);

    List<SyncInfo> getCurrentSyncs();
    List<SyncInfo> getCurrentSyncsAsUser(int userId);

    /**
     * Returns the types of the SyncAdapters that are registered with the system.
     * @return Returns the types of the SyncAdapters that are registered with the system.
     */
    @UnsupportedAppUsage
    SyncAdapterType[] getSyncAdapterTypes();
    SyncAdapterType[] getSyncAdapterTypesAsUser(int userId);

    String[] getSyncAdapterPackagesForAuthorityAsUser(String authority, int userId);

    /**
     * Returns true if there is currently a operation for the given account/authority or service
     * actively being processed.
     * @param account account for authority, must be null if cname is non-null.
     * @param providerName name of provider, must be null if cname is non-null.
     * @param cname component to identify sync service, must be null if account/providerName are
     * non-null.
     */
    @UnsupportedAppUsage
    boolean isSyncActive(in Account account, String authority, in ComponentName cname);

    /**
     * Returns the status that matches the authority. If there are multiples accounts for
     * the authority, the one with the latest "lastSuccessTime" status is returned.
     * @param account account for authority, must be null if cname is non-null.
     * @param providerName name of provider, must be null if cname is non-null.
     * @param cname component to identify sync service, must be null if account/providerName are
     * non-null.
     */
    SyncStatusInfo getSyncStatus(in Account account, String authority, in ComponentName cname);
    SyncStatusInfo getSyncStatusAsUser(in Account account, String authority, in ComponentName cname,
            int userId);

    /**
     * Return true if the pending status is true of any matching authorities.
     * @param account account for authority, must be null if cname is non-null.
     * @param providerName name of provider, must be null if cname is non-null.
     * @param cname component to identify sync service, must be null if account/providerName are
     * non-null.
     */
    boolean isSyncPending(in Account account, String authority, in ComponentName cname);
    boolean isSyncPendingAsUser(in Account account, String authority, in ComponentName cname,
            int userId);

    void addStatusChangeListener(int mask, ISyncStatusObserver callback);
    void removeStatusChangeListener(ISyncStatusObserver callback);

    void putCache(in String packageName, in Uri key, in Bundle value, int userId);
    Bundle getCache(in String packageName, in Uri key, int userId);

    void resetTodayStats();
}
