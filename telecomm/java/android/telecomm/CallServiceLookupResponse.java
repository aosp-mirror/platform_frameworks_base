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

import android.os.RemoteException;

import com.android.internal.telecomm.ICallServiceLookupResponse;

import java.util.List;

/**
 * Used by {@link CallServiceProvider} to return a list of {@link CallServiceDescriptor}s.
 */
public final class CallServiceLookupResponse {
    private final ICallServiceLookupResponse mResponse;

    /**
     * {@hide}
     */
    public CallServiceLookupResponse(ICallServiceLookupResponse response) {
        mResponse = response;
    }

    /**
     * Passes the sorted list of preferred {@link CallServiceDescriptor}s back to Telecomm.  Used
     * in the context of attempting to place a pending outgoing call.
     *
     * @param callServiceDescriptors The set of call-service descriptors from
     * {@link CallServiceProvider}.
     */
    public void setCallServiceDescriptors(List<CallServiceDescriptor> callServiceDescriptors) {
        try {
            mResponse.setCallServiceDescriptors(callServiceDescriptors);
        } catch (RemoteException e) {
        }
    }
}
