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
import android.annotation.SystemApi;
import android.telephony.ims.DelegateMessageCallback;
import android.telephony.ims.DelegateRequest;
import android.telephony.ims.DelegateStateCallback;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.aidl.ISipTransport;

import java.util.concurrent.Executor;

/**
 * The ImsService implements this class to manage the creation and destruction of
 * {@link SipDelegate}s.
 *
 * {@link SipDelegate}s allow the ImsService to forward SIP traffic generated and consumed by IMS
 * applications as a delegate to the associated carrier's IMS Network in order to support using a
 * single IMS registration for all MMTEL and RCS signalling traffic.
 * @hide
 */
@SystemApi
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
     * The ImsService implements this method to handle requests to create a new {@link SipDelegate}
     * for subscription associated with it.
     *
     * @param request A SIP delegate request containing the parameters that the remote RCS
     * application wishes to use.
     * @param dc A callback back to the remote application to be used to communicate state callbacks
     *           for the SipDelegate.
     * @param mc A callback back to the remote application to be used to send SIP messages to the
     *           remote application and acknowledge the sending of outgoing SIP messages.
     * @hide
     */
    public void createSipDelegate(@NonNull DelegateRequest request,
            @NonNull DelegateStateCallback dc, @NonNull DelegateMessageCallback mc) {

    }

    /**
     * Destroys the SipDelegate associated with a remote IMS application. After the delegate is
     * destroyed, SipDelegate#onDestroy should be called to notify listeners of its destruction to
     * release resources.
     * @param delegate The delegate to be modified.
     * @param reason The reason the remote connection to this SipDelegate is being destroyed.
     * @hide
     */
    public void destroySipDelegate(@NonNull SipDelegate delegate,
            @SipDelegateManager.SipDelegateDestroyReason int reason) {

    }

    /**
     * @return The IInterface used by the framework.
     * @hide
     */
    public ISipTransport getBinder() {
        return mSipTransportImpl;
    }
}
