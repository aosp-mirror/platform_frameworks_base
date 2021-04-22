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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.RemoteException;
import android.telephony.ims.DelegateMessageCallback;
import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.DelegateStateCallback;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateImsConfiguration;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;
import android.telephony.ims.stub.SipDelegate;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Implementation of callbacks by wrapping the internal AIDL from telephony. Also implements
 * ISipDelegate internally when {@link DelegateStateCallback#onCreated(SipDelegate, Set)} is called
 * in order to trampoline events back to telephony.
 * @hide
 */
public class SipDelegateAidlWrapper implements DelegateStateCallback, DelegateMessageCallback {
    private static final String LOG_TAG = "SipDelegateAW";

    private final ISipDelegate.Stub mDelegateBinder = new ISipDelegate.Stub() {
        @Override
        public void sendMessage(SipMessage sipMessage, long configVersion) {
            SipDelegate d = mDelegate;
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> d.sendMessage(sipMessage, configVersion));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyMessageReceived(String viaTransactionId)  {
            SipDelegate d = mDelegate;
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> d.notifyMessageReceived(viaTransactionId));
            } finally {
                Binder.restoreCallingIdentity(token);
            }

        }

        @Override
        public void notifyMessageReceiveError(String viaTransactionId, int reason) {
            SipDelegate d = mDelegate;
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> d.notifyMessageReceiveError(viaTransactionId, reason));
            } finally {
                Binder.restoreCallingIdentity(token);
            }

        }

        @Override
        public void cleanupSession(String callId)  {
            SipDelegate d = mDelegate;
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> d.cleanupSession(callId));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    private final ISipDelegateMessageCallback mMessageBinder;
    private final ISipDelegateStateCallback mStateBinder;
    private final Executor mExecutor;

    private volatile SipDelegate mDelegate;

    public SipDelegateAidlWrapper(Executor executor, ISipDelegateStateCallback stateBinder,
            ISipDelegateMessageCallback messageBinder) {
        mExecutor = executor;
        mStateBinder = stateBinder;
        mMessageBinder = messageBinder;
    }

    @Override
    public void onMessageReceived(SipMessage message) {
        try {
            mMessageBinder.onMessageReceived(message);
        } catch (RemoteException e) {
            // BinderDied will be called on SipTransport instance to trigger destruction. Notify
            // failure message failure locally for now.
            SipDelegate d = mDelegate;
            if (d != null) {
                notifyLocalMessageFailedToBeReceived(message,
                        SipDelegateManager.MESSAGE_FAILURE_REASON_DELEGATE_DEAD);
            }
        }
    }

    @Override
    public void onMessageSent(String viaTransactionId) {
        try {
            mMessageBinder.onMessageSent(viaTransactionId);
        } catch (RemoteException e) {
            // BinderDied will trigger destroySipDelegate, so just ignore this locally.
        }
    }

    @Override
    public void onMessageSendFailure(String viaTransactionId, int reason) {
        try {
            mMessageBinder.onMessageSendFailure(viaTransactionId, reason);
        } catch (RemoteException e) {
            // BinderDied will trigger destroySipDelegate, so just ignore this locally.
        }
    }

    @Override
    public void onCreated(@NonNull SipDelegate delegate,
            @Nullable Set<FeatureTagState> deniedTags) {
        mDelegate = delegate;
        try {
            mStateBinder.onCreated(mDelegateBinder, new ArrayList<>(deniedTags));
        } catch (RemoteException e) {
            // BinderDied will trigger destroySipDelegate, so just ignore this locally.
        }
    }

    @Override
    public void onFeatureTagRegistrationChanged(DelegateRegistrationState registrationState) {
        try {
            mStateBinder.onFeatureTagRegistrationChanged(registrationState);
        } catch (RemoteException e) {
            // BinderDied will trigger destroySipDelegate, so just ignore this locally.
        }
    }

    @Override
    public void onImsConfigurationChanged(@NonNull SipDelegateImsConfiguration config) {
        try {
            mStateBinder.onImsConfigurationChanged(config);
        } catch (RemoteException e) {
            // BinderDied will trigger destroySipDelegate, so just ignore this locally.
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull SipDelegateConfiguration config) {
        try {
            mStateBinder.onConfigurationChanged(config);
        } catch (RemoteException e) {
            // BinderDied will trigger destroySipDelegate, so just ignore this locally.
        }
    }

    @Override
    public void onDestroyed(int reasonCode) {
        mDelegate = null;
        try {
            mStateBinder.onDestroyed(reasonCode);
        } catch (RemoteException e) {
            // Do not worry about this if the remote side is already dead.
        }
    }

    public SipDelegate getDelegate()  {
        return mDelegate;
    }

    public ISipDelegate getDelegateBinder() {
        return mDelegateBinder;
    }

    private void notifyLocalMessageFailedToBeReceived(SipMessage m, int reason) {
        String transactionId = m.getViaBranchParameter();
        SipDelegate d = mDelegate;
        if (d != null) {
            mExecutor.execute(() -> d.notifyMessageReceiveError(transactionId, reason));
        }
    }
}
