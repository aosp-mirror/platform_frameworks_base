/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.ims.stub;

import android.os.RemoteException;

import com.android.ims.internal.IImsExternalCallStateListener;
import com.android.ims.internal.IImsMultiEndpoint;

/**
 * Base implementation of ImsMultiEndpoint, which implements stub versions of the methods
 * in the IImsMultiEndpoint AIDL. Override the methods that your implementation of
 * ImsMultiEndpoint supports.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsMultiEndpoint maintained by other ImsServices.
 *
 * @hide
 */

public class ImsMultiEndpointImplBase extends IImsMultiEndpoint.Stub {

    /**
     * Sets the listener.
     */
    @Override
    public void setListener(IImsExternalCallStateListener listener) throws RemoteException {

    }

    /**
     * Query API to get the latest Dialog Event Package information
     * Should be invoked only after setListener is done
     */
    @Override
    public void requestImsExternalCallStateInfo() throws RemoteException {

    }
}
