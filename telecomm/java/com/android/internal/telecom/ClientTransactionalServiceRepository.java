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

package com.android.internal.telecom;

import android.telecom.PhoneAccountHandle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public class ClientTransactionalServiceRepository {

    private static final Map<PhoneAccountHandle, ClientTransactionalServiceWrapper> LOOKUP_TABLE =
            new ConcurrentHashMap<>();

    /**
     * creates a new {@link ClientTransactionalServiceWrapper} if this is the first call being
     * tracked for a particular package Or adds a new call for an existing
     * {@link ClientTransactionalServiceWrapper}
     *
     * @param phoneAccountHandle for a particular package requesting to create a call
     * @return the {@link ClientTransactionalServiceWrapper} that is tied tot the PhoneAccountHandle
     */
    public ClientTransactionalServiceWrapper addNewCallForTransactionalServiceWrapper(
            PhoneAccountHandle phoneAccountHandle) {

        ClientTransactionalServiceWrapper service = null;
        if (!hasExistingServiceWrapper(phoneAccountHandle)) {
            service = new ClientTransactionalServiceWrapper(phoneAccountHandle, this);
        } else {
            service = getTransactionalServiceWrapper(phoneAccountHandle);
        }

        LOOKUP_TABLE.put(phoneAccountHandle, service);

        return service;
    }

    private ClientTransactionalServiceWrapper getTransactionalServiceWrapper(
            PhoneAccountHandle pah) {
        return LOOKUP_TABLE.get(pah);
    }

    private boolean hasExistingServiceWrapper(PhoneAccountHandle pah) {
        return LOOKUP_TABLE.containsKey(pah);
    }

    /**
     * @param pah that is tied to a particular package with potential tracked calls
     * @return if the {@link ClientTransactionalServiceWrapper} was successfully removed
     */
    public boolean removeServiceWrapper(PhoneAccountHandle pah) {
        if (!hasExistingServiceWrapper(pah)) {
            return false;
        }
        LOOKUP_TABLE.remove(pah);
        return true;
    }

    /**
     * @param pah    that is tied to a particular package with potential tracked calls
     * @param callId of the TransactionalCall that you want to remove
     * @return if the call was successfully removed from the service wrapper
     */
    public boolean removeCallFromServiceWrapper(PhoneAccountHandle pah, String callId) {
        if (!hasExistingServiceWrapper(pah)) {
            return false;
        }
        ClientTransactionalServiceWrapper service = LOOKUP_TABLE.get(pah);
        service.untrackCall(callId);
        return true;
    }

}
