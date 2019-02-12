/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony.ims;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.ims.aidl.IRcs;

/**
 * A wrapper class around RPC calls that {@link RcsMessageStore} APIs to minimize boilerplate code.
 *
 * @hide - not meant for public use
 */
class RcsControllerCall {
    static <R> R call(RcsServiceCall<R> serviceCall) throws RcsMessageStoreException {
        IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_RCS_SERVICE));
        if (iRcs == null) {
            throw new RcsMessageStoreException("Could not connect to RCS storage service");
        }

        try {
            return serviceCall.methodOnIRcs(iRcs);
        } catch (RemoteException exception) {
            throw new RcsMessageStoreException(exception.getMessage());
        }
    }

    static void callWithNoReturn(RcsServiceCallWithNoReturn serviceCall)
            throws RcsMessageStoreException {
        IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_RCS_SERVICE));
        if (iRcs == null) {
            throw new RcsMessageStoreException("Could not connect to RCS storage service");
        }

        try {
            serviceCall.methodOnIRcs(iRcs);
        } catch (RemoteException exception) {
            throw new RcsMessageStoreException(exception.getMessage());
        }
    }

    interface RcsServiceCall<R> {
        R methodOnIRcs(IRcs iRcs) throws RemoteException;
    }

    interface RcsServiceCallWithNoReturn {
        void methodOnIRcs(IRcs iRcs) throws RemoteException;
    }
}
