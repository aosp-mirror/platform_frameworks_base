/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion;

import android.companion.AssociationInfo;

import com.android.server.companion.datatransfer.contextsync.CrossDeviceCall;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceSyncControllerCallback;

import java.util.Collection;

/**
 * Companion Device Manager Local System Service Interface.
 */
public interface CompanionDeviceManagerServiceInternal {

    /**
     * Remove idle self-managed associations.
     */
    void removeInactiveSelfManagedAssociations();

    /**
     * Registers a callback from an InCallService / ConnectionService to CDM to process sync
     * requests and perform call control actions.
     */
    void registerCallMetadataSyncCallback(CrossDeviceSyncControllerCallback callback,
            @CrossDeviceSyncControllerCallback.Type int type);

    /**
     * Requests a sync from an InCallService / ConnectionService to CDM, for the given association
     * and message.
     */
    void sendCrossDeviceSyncMessage(int associationId, byte[] message);

    /** Sends the provided message to all active associations for the specified user. */
    void sendCrossDeviceSyncMessageToAllDevices(int userId, byte[] message);

    /** Mark a call id as "self owned" (i.e. this device owns the canonical call). */
    void addSelfOwnedCallId(String callId);

    /** Unmark a call id as "self owned" (i.e. this device no longer owns the canonical call). */
    void removeSelfOwnedCallId(String callId);

    /**
     * Requests a sync from an InCallService to CDM, for the given user and call metadata.
     */
    void crossDeviceSync(int userId, Collection<CrossDeviceCall> calls);

    /**
     * Requests a sync from an InCallService to CDM, for the given association and call metadata.
     */
    void crossDeviceSync(AssociationInfo associationInfo, Collection<CrossDeviceCall> calls);
}
