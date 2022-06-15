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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.ims.DelegateMessageCallback;
import android.telephony.ims.DelegateRequest;
import android.telephony.ims.DelegateStateCallback;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.aidl.ISipDelegate;
import android.telephony.ims.aidl.ISipDelegateMessageCallback;
import android.telephony.ims.aidl.ISipDelegateStateCallback;
import android.telephony.ims.aidl.ISipTransport;
import android.telephony.ims.aidl.SipDelegateAidlWrapper;
import android.util.Log;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Objects;
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
    private static final String LOG_TAG = "SipTransportIB";

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            // Clean up all binders in this case.
            mBinderExecutor.execute(() -> binderDiedInternal(null));
        }
        @Override
        public void binderDied(IBinder who) {
            mBinderExecutor.execute(() -> binderDiedInternal(who));
        }
    };

    private final ISipTransport.Stub mSipTransportImpl = new ISipTransport.Stub() {
        @Override
        public void createSipDelegate(int subId, DelegateRequest request,
                ISipDelegateStateCallback dc, ISipDelegateMessageCallback mc) {
            final long token = Binder.clearCallingIdentity();
            try {
                mBinderExecutor.execute(() -> createSipDelegateInternal(subId, request, dc, mc));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void destroySipDelegate(ISipDelegate delegate, int reason) {
            final long token = Binder.clearCallingIdentity();
            try {
                mBinderExecutor.execute(() -> destroySipDelegateInternal(delegate, reason));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    private final Executor mBinderExecutor;
    private final ArrayList<SipDelegateAidlWrapper> mDelegates = new ArrayList<>();

    /**
     * Create an implementation of SipTransportImplBase.
     *
     * @param executor The executor that remote calls from the framework will be called on. This
     *                 includes the methods here as well as the methods in {@link SipDelegate}.
     */
    public SipTransportImplBase(@NonNull Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }

        mBinderExecutor = executor;
    }

    /**
     * Called by the Telephony framework to request the creation of a new {@link SipDelegate}.
     * <p>
     * The implementation must call
     * {@link DelegateStateCallback#onCreated(SipDelegate, java.util.Set)} with
     * the {@link SipDelegate} that is associated with the {@link DelegateRequest}.
     * <p>
     * This method will be called on the Executor specified in
     * {@link SipTransportImplBase#SipTransportImplBase(Executor)}.
     *
     * @param subscriptionId The subscription ID associated with the requested {@link SipDelegate}.
     * @param request A SIP delegate request containing the parameters that the remote RCS
     * application wishes to use.
     * @param dc A callback back to the remote application to be used to communicate state callbacks
     *           for the SipDelegate.
     * @param mc A callback back to the remote application to be used to send SIP messages to the
     *           remote application and acknowledge the sending of outgoing SIP messages.
     */
    // executor used is defined in the constructor.
    @SuppressLint("ExecutorRegistration")
    public void createSipDelegate(int subscriptionId, @NonNull DelegateRequest request,
            @NonNull DelegateStateCallback dc, @NonNull DelegateMessageCallback mc) {
        throw new UnsupportedOperationException("createSipDelegate not implemented!");
    }

    /**
     * Destroys the SipDelegate associated with a remote IMS application.
     * <p>
     * After the delegate is destroyed, {@link DelegateStateCallback#onDestroyed(int)} must be
     * called to notify listeners of its destruction to release associated resources.
     * <p>
     * This method will be called on the Executor specified in
     * {@link SipTransportImplBase#SipTransportImplBase(Executor)}.
     * @param delegate The delegate to be destroyed.
     * @param reason The reason the remote connection to this {@link SipDelegate} is being
     *         destroyed.
     */
    public void destroySipDelegate(@NonNull SipDelegate delegate,
            @SipDelegateManager.SipDelegateDestroyReason int reason) {
        throw new UnsupportedOperationException("destroySipDelegate not implemented!");
    }

    private void createSipDelegateInternal(int subId, DelegateRequest r,
            ISipDelegateStateCallback cb, ISipDelegateMessageCallback mc) {
        SipDelegateAidlWrapper wrapper = new SipDelegateAidlWrapper(mBinderExecutor, cb, mc);
        mDelegates.add(wrapper);
        linkDeathRecipient(wrapper);
        createSipDelegate(subId, r, wrapper, wrapper);
    }

    private void destroySipDelegateInternal(ISipDelegate d, int reason) {
        SipDelegateAidlWrapper result = null;
        for (SipDelegateAidlWrapper w : mDelegates) {
            if (Objects.equals(d, w.getDelegateBinder())) {
                result = w;
                break;
            }
        }

        if (result != null) {
            unlinkDeathRecipient(result);
            mDelegates.remove(result);
            destroySipDelegate(result.getDelegate(), reason);
        } else {
            Log.w(LOG_TAG, "destroySipDelegateInternal, could not findSipDelegate corresponding to "
                    + d);
        }
    }

    private void linkDeathRecipient(SipDelegateAidlWrapper w) {
        try {
            w.getStateCallbackBinder().asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "linkDeathRecipient, remote process already died, cleaning up.");
            mDeathRecipient.binderDied(w.getStateCallbackBinder().asBinder());
        }
    }

    private void unlinkDeathRecipient(SipDelegateAidlWrapper w) {
        try {
            w.getStateCallbackBinder().asBinder().unlinkToDeath(mDeathRecipient, 0);
        } catch (NoSuchElementException e) {
            // Ignore this case.
        }
    }

    private void binderDiedInternal(IBinder who) {
        for (SipDelegateAidlWrapper w : mDelegates) {
            // If the binder itself was not given from the platform, just clean up all binders.
            if (who == null || w.getStateCallbackBinder().asBinder().equals(who))  {
                Log.w(LOG_TAG, "Binder death detected for " + w + ", calling destroy and "
                        + "removing.");
                mDelegates.remove(w);
                destroySipDelegate(w.getDelegate(),
                        SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_SERVICE_DEAD);
                return;
            }
        }
        Log.w(LOG_TAG, "Binder death detected for IBinder " + who + ", but couldn't find matching "
                + "SipDelegate");
    }

    /**
     * @return The IInterface used by the framework.
     * @hide
     */
    public ISipTransport getBinder() {
        return mSipTransportImpl;
    }
}
