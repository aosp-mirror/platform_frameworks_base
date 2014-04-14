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

package android.telecomm;

import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.telecomm.CallServiceDescriptor;

import com.android.internal.telecomm.ICallServiceSelectorAdapter;

import java.util.List;

/**
 * Provides methods for ICallServiceSelector implementations to interact with Telecomm.
 */
public final class CallServiceSelectorAdapter {
    private final ICallServiceSelectorAdapter mAdapter;

    /**
     * {@hide}
     */
    public CallServiceSelectorAdapter(ICallServiceSelectorAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Records the sorted set of call services that are preferred by the corresponding
     * call-service selector.
     *
     * @param callId The ID of the call to complete.
     * @param selectedCallServiceDescriptors The prioritized list of preferred call-service
     *        descriptors to use for completing the call.
     */
    public void setSelectedCallServices(
            String callId,
            List<CallServiceDescriptor> selectedCallServiceDescriptors) {
        try {
            mAdapter.setSelectedCallServices(callId, selectedCallServiceDescriptors);
        } catch (RemoteException e) {
        }
    }

    /**
     * Cancels the specified outgoing call.
     *
     * @param callId The ID of the call to cancel.
     */
    public void cancelOutgoingCall(String callId) {
        try {
            mAdapter.cancelOutgoingCall(callId);
        } catch (RemoteException e) {
        }
    }

    /**
     * Associates handoff information with an ongoing call. Calls can switch from one call service
     * to another. Setting handle to a non-null value marks the call as switchable.
     *
     * @param callId The ID of the call to set handoff information for.
     * @param handle The handle used to place the call when switching.
     * @param extras Optional extra that's attached to the call.
     */
    public void setHandoffInfo(String callId, Uri handle, Bundle extras) {
        try {
            mAdapter.setHandoffInfo(callId, handle, extras);
        } catch (RemoteException e) {
        }
    }
}
