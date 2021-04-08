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

package android.telephony.ims.aidl;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipDelegateImsConfiguration;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;
import android.telephony.ims.stub.DelegateConnectionMessageCallback;
import android.telephony.ims.stub.DelegateConnectionStateCallback;
import android.telephony.ims.stub.SipDelegate;
import android.util.ArraySet;
import android.util.Log;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wrapper class implementing {@link SipDelegateConnection} using AIDL, which is returned to the
 * local process. Also holds a reference to incoming connection message and state AIDL impl to
 * trampoline events to callbacks as well as notify the local process in the event that the remote
 * process becomes unavailable.
 * <p>
 * When the remote {@link SipDelegate} is created, this instance tracks the
 * {@link ISipDelegate} associated with it and implements the
 * {@link SipDelegateConnection} sent back to the local callback.
 * @hide
 */
public class SipDelegateConnectionAidlWrapper implements SipDelegateConnection,
        IBinder.DeathRecipient {
    private static final String LOG_TAG = "SipDelegateCAW";

    private final ISipDelegateConnectionStateCallback.Stub mStateBinder =
            new ISipDelegateConnectionStateCallback.Stub() {
        @Override
        public void onCreated(ISipDelegate c) {
            associateSipDelegate(c);
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mStateCallback.onCreated(SipDelegateConnectionAidlWrapper.this));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onFeatureTagStatusChanged(DelegateRegistrationState registrationState,
                List<FeatureTagState> deniedFeatureTags) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mStateCallback.onFeatureTagStatusChanged(registrationState,
                                new ArraySet<>(deniedFeatureTags)));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onImsConfigurationChanged(SipDelegateImsConfiguration registeredSipConfig) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mStateCallback.onImsConfigurationChanged(registeredSipConfig));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onDestroyed(int reason) {
            invalidateSipDelegateBinder();
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mStateCallback.onDestroyed(reason));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    private final ISipDelegateMessageCallback.Stub mMessageBinder =
            new ISipDelegateMessageCallback.Stub() {
                @Override
                public void onMessageReceived(SipMessage message) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mExecutor.execute(() ->
                                mMessageCallback.onMessageReceived(message));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }

                @Override
                public void onMessageSent(String viaTransactionId) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mExecutor.execute(() ->
                                mMessageCallback.onMessageSent(viaTransactionId));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }

                @Override
                public void onMessageSendFailure(String viaTransactionId, int reason) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mExecutor.execute(() ->
                                mMessageCallback.onMessageSendFailure(viaTransactionId, reason));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };


    private final Executor mExecutor;
    private final DelegateConnectionStateCallback mStateCallback;
    private final DelegateConnectionMessageCallback mMessageCallback;
    private final AtomicReference<ISipDelegate> mDelegateBinder =
            new AtomicReference<>();

    /**
     * Wrap the local state and message callbacks, calling the implementation of these interfaces
     * when the remote process calls these methods.
     */
    public SipDelegateConnectionAidlWrapper(Executor executor,
            DelegateConnectionStateCallback stateCallback,
            DelegateConnectionMessageCallback messageCallback) {
        mExecutor = executor;
        mStateCallback = stateCallback;
        mMessageCallback = messageCallback;
    }

    @Override
    public void sendMessage(SipMessage sipMessage, long configVersion) {
        try {
            ISipDelegate conn = getSipDelegateBinder();
            if (conn == null) {
                notifyLocalMessageFailedToSend(sipMessage,
                        SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_CLOSED);
                return;
            }
            conn.sendMessage(sipMessage, configVersion);
        } catch (RemoteException e) {
            notifyLocalMessageFailedToSend(sipMessage,
                        SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_DEAD);
        }
    }

    @Override
    public void notifyMessageReceived(String viaTransactionId) {
        try {
            ISipDelegate conn = getSipDelegateBinder();
            if (conn == null) {
                return;
            }
            conn.notifyMessageReceived(viaTransactionId);
        } catch (RemoteException e) {
            // Nothing to do here, app will eventually get remote death callback.
        }
    }

    @Override
    public void notifyMessageReceiveError(String viaTransactionId, int reason) {
        try {
            ISipDelegate conn = getSipDelegateBinder();
            if (conn == null) {
                return;
            }
            conn.notifyMessageReceiveError(viaTransactionId, reason);
        } catch (RemoteException e) {
            // Nothing to do here, app will eventually get remote death callback.
        }
    }

    @Override
    public void closeDialog(String callId) {
        try {
            ISipDelegate conn = getSipDelegateBinder();
            if (conn == null) {
                return;
            }
            conn.closeDialog(callId);
        } catch (RemoteException e) {
            // Nothing to do here, app will eventually get remote death callback.
        }
    }

    // Also called upon IImsRcsController death (telephony process dies).
    @Override
    public void binderDied() {
        invalidateSipDelegateBinder();
        mExecutor.execute(() -> mStateCallback.onDestroyed(
                SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SERVICE_DEAD));
    }

    /**
     * @return Implementation of state binder.
     */
    public ISipDelegateConnectionStateCallback getStateCallbackBinder() {
        return mStateBinder;
    }

    /**
     * @return Implementation of message binder.
     */
    public ISipDelegateMessageCallback getMessageCallbackBinder() {
        return mMessageBinder;
    }

    /**
     * @return The ISipDelegateConnection associated with this wrapper.
     */
    public ISipDelegate getSipDelegateBinder() {
        return mDelegateBinder.get();
    }

    private void associateSipDelegate(ISipDelegate c) {
        if (c != null) {
            try {
                c.asBinder().linkToDeath(this, 0 /*flags*/);
            } catch (RemoteException e) {
                // already dead.
                c = null;
            }
        }
        mDelegateBinder.set(c);
    }

    private void invalidateSipDelegateBinder() {
        ISipDelegate oldVal = mDelegateBinder.getAndUpdate((unused) -> null);
        if (oldVal != null) {
            try {
                oldVal.asBinder().unlinkToDeath(this, 0 /*flags*/);
            } catch (NoSuchElementException e) {
                Log.i(LOG_TAG, "invalidateSipDelegateBinder: " + e);
            }
        }
    }

    private void notifyLocalMessageFailedToSend(SipMessage m, int reason) {
        String transactionId = m.getViaBranchParameter();
        mExecutor.execute(() -> mMessageCallback.onMessageSendFailure(transactionId, reason));
    }
}
