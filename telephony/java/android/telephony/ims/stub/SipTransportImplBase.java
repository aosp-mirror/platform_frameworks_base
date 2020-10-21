/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.ims.stub;

import android.annotation.NonNull;
import android.telephony.ims.aidl.ISipTransport;

import java.util.concurrent.Executor;

/**
 * Manages the creation and destruction of SipDelegates in order to proxy SIP traffic to other
 * IMS applications in order to support IMS single registration.
 *
 * @hide Until there is an implementation, keep this hidden
 */
public class SipTransportImplBase {

    private final Executor mBinderExecutor;
    private final ISipTransport mSipTransportImpl = new ISipTransport.Stub() {

    };

    /**
     * Create an implementation of SipTransportImplBase.
     *
     * @param executor The executor that remote calls from the framework should be called on.
     */
    public SipTransportImplBase(@NonNull Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }

        mBinderExecutor = executor;
    }

    /**
     * @return The IInterface used by the framework.
     * @hide
     */
    public ISipTransport getBinder() {
        return mSipTransportImpl;
    }
}
