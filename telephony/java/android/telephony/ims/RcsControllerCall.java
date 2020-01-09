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
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.ims.aidl.IRcsMessage;

/**
 * A wrapper class around RPC calls that {@link RcsMessageManager} APIs to minimize boilerplate
 * code.
 *
 * @hide - not meant for public use
 */
class RcsControllerCall {
    private final Context mContext;

    RcsControllerCall(Context context) {
        mContext = context;
    }

    <R> R call(RcsServiceCall<R> serviceCall) throws RcsMessageStoreException {
        IRcsMessage iRcsMessage = IRcsMessage.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getTelephonyRcsMessageServiceRegisterer()
                        .get());
        if (iRcsMessage == null) {
            throw new RcsMessageStoreException("Could not connect to RCS storage service");
        }

        try {
            return serviceCall.methodOnIRcs(iRcsMessage, mContext.getOpPackageName());
        } catch (RemoteException exception) {
            throw new RcsMessageStoreException(exception.getMessage());
        }
    }

    void callWithNoReturn(RcsServiceCallWithNoReturn serviceCall)
            throws RcsMessageStoreException {
        call((iRcsMessage, callingPackage) -> {
            serviceCall.methodOnIRcs(iRcsMessage, callingPackage);
            return null;
        });
    }

    interface RcsServiceCall<R> {
        R methodOnIRcs(IRcsMessage iRcs, String callingPackage) throws RemoteException;
    }

    interface RcsServiceCallWithNoReturn {
        void methodOnIRcs(IRcsMessage iRcs, String callingPackage) throws RemoteException;
    }
}
