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

import android.content.ActiveSyncInfo;
import android.content.ISyncStatusObserver;
import android.content.SyncStatusInfo;
import android.net.Uri;
import android.os.Bundle;
import android.database.IContentObserver;

/**
 * @hide
 */
interface IContentService {
    void registerContentObserver(in Uri uri, boolean notifyForDescendentsn,
            IContentObserver observer);
    void unregisterContentObserver(IContentObserver observer);

    void notifyChange(in Uri uri, IContentObserver observer,
            boolean observerWantsSelfNotifications, boolean syncToNetwork);

    void startSync(in Uri url, in Bundle extras);
    void cancelSync(in Uri uri);
    
    /**
     * Check if the provider should be synced when a network tickle is received
     * @param providerName the provider whose setting we are querying
     * @return true of the provider should be synced when a network tickle is received
     */
    boolean getSyncProviderAutomatically(String providerName);

    /**
     * Set whether or not the provider is synced when it receives a network tickle.
     *
     * @param providerName the provider whose behavior is being controlled
     * @param sync true if the provider should be synced when tickles are received for it
     */
    void setSyncProviderAutomatically(String providerName, boolean sync);

    void setListenForNetworkTickles(boolean flag);

    boolean getListenForNetworkTickles();
    
    /**
     * Returns true if there is currently a sync operation for the given
     * account or authority in the pending list, or actively being processed.
     */
    boolean isSyncActive(String account, String authority);
    
    ActiveSyncInfo getActiveSync();
    
    /**
     * Returns the status that matches the authority. If there are multiples accounts for
     * the authority, the one with the latest "lastSuccessTime" status is returned.
     * @param authority the authority whose row should be selected
     * @return the SyncStatusInfo for the authority, or null if none exists
     */
    SyncStatusInfo getStatusByAuthority(String authority);

    /**
     * Return true if the pending status is true of any matching authorities.
     */
    boolean isAuthorityPending(String account, String authority);
    
    void addStatusChangeListener(int mask, ISyncStatusObserver callback);
    
    void removeStatusChangeListener(ISyncStatusObserver callback);
}
