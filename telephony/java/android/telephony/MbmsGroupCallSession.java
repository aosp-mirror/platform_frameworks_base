/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.GroupCall;
import android.telephony.mbms.GroupCallCallback;
import android.telephony.mbms.InternalGroupCallCallback;
import android.telephony.mbms.InternalGroupCallSessionCallback;
import android.telephony.mbms.MbmsErrors;
import android.telephony.mbms.MbmsGroupCallSessionCallback;
import android.telephony.mbms.MbmsUtils;
import android.telephony.mbms.vendor.IMbmsGroupCallService;
import android.util.ArraySet;
import android.util.Log;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class provides functionality for accessing group call functionality over MBMS.
 */
public class MbmsGroupCallSession implements AutoCloseable {
    private static final String LOG_TAG = "MbmsGroupCallSession";

    /**
     * Service action which must be handled by the middleware implementing the MBMS group call
     * interface.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String MBMS_GROUP_CALL_SERVICE_ACTION =
            "android.telephony.action.EmbmsGroupCall";

    /**
     * Metadata key that specifies the component name of the service to bind to for group calls.
     * @hide
     */
    @TestApi
    public static final String MBMS_GROUP_CALL_SERVICE_OVERRIDE_METADATA =
            "mbms-group-call-service-override";

    private static AtomicBoolean sIsInitialized = new AtomicBoolean(false);

    private AtomicReference<IMbmsGroupCallService> mService = new AtomicReference<>(null);
    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            sIsInitialized.set(false);
            mInternalCallback.onError(MbmsErrors.ERROR_MIDDLEWARE_LOST,
                    "Received death notification");
        }
    };

    private InternalGroupCallSessionCallback mInternalCallback;
    private ServiceConnection mServiceConnection;
    private Set<GroupCall> mKnownActiveGroupCalls = new ArraySet<>();

    private final Context mContext;
    private int mSubscriptionId;

    /** @hide */
    private MbmsGroupCallSession(Context context, Executor executor, int subscriptionId,
            MbmsGroupCallSessionCallback callback) {
        mContext = context;
        mSubscriptionId = subscriptionId;
        mInternalCallback = new InternalGroupCallSessionCallback(callback, executor);
    }

    /**
     * Create a new {@link MbmsGroupCallSession} using the given subscription ID.
     *
     * You may only have one instance of {@link MbmsGroupCallSession} per UID. If you call this
     * method while there is an active instance of {@link MbmsGroupCallSession} in your process
     * (in other words, one that has not had {@link #close()} called on it), this method will
     * throw an {@link IllegalStateException}. If you call this method in a different process
     * running under the same UID, an error will be indicated via
     * {@link MbmsGroupCallSessionCallback#onError(int, String)}.
     *
     * Note that initialization may fail asynchronously. If you wish to try again after you
     * receive such an asynchronous error, you must call {@link #close()} on the instance of
     * {@link MbmsGroupCallSession} that you received before calling this method again.
     *
     * @param context The {@link Context} to use.
     * @param subscriptionId The subscription ID to use.
     * @param executor The executor on which you wish to execute callbacks.
     * @param callback A callback object on which you wish to receive results of asynchronous
     *                 operations.
     * @return An instance of {@link MbmsGroupCallSession}, or null if an error occurred.
     */
    public static @Nullable MbmsGroupCallSession create(@NonNull Context context,
            int subscriptionId, @NonNull Executor executor,
            final @NonNull MbmsGroupCallSessionCallback callback) {
        if (!sIsInitialized.compareAndSet(false, true)) {
            throw new IllegalStateException("Cannot create two instances of MbmsGroupCallSession");
        }
        MbmsGroupCallSession session = new MbmsGroupCallSession(context, executor,
                subscriptionId, callback);

        final int result = session.bindAndInitialize();
        if (result != MbmsErrors.SUCCESS) {
            sIsInitialized.set(false);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onError(result, null);
                }
            });
            return null;
        }
        return session;
    }

    /**
     * Create a new {@link MbmsGroupCallSession} using the system default data subscription ID.
     * See {@link #create(Context, int, Executor, MbmsGroupCallSessionCallback)}.
     */
    public static @Nullable MbmsGroupCallSession create(@NonNull Context context,
            @NonNull Executor executor, @NonNull MbmsGroupCallSessionCallback callback) {
        return create(context, SubscriptionManager.getDefaultSubscriptionId(), executor, callback);
    }

    /**
     * Terminates this instance. Also terminates
     * any group calls spawned from this instance as if
     * {@link GroupCall#close()} had been called on them. After this method returns,
     * no further callbacks originating from the middleware will be enqueued on the provided
     * instance of {@link MbmsGroupCallSessionCallback}, but callbacks that have already been
     * enqueued will still be delivered.
     *
     * It is safe to call {@link #create(Context, int, Executor, MbmsGroupCallSessionCallback)} to
     * obtain another instance of {@link MbmsGroupCallSession} immediately after this method
     * returns.
     *
     * May throw an {@link IllegalStateException}
     */
    public void close() {
        try {
            IMbmsGroupCallService groupCallService = mService.get();
            if (groupCallService == null || mServiceConnection == null) {
                // Ignore and return, assume already disposed.
                return;
            }
            groupCallService.dispose(mSubscriptionId);
            for (GroupCall s : mKnownActiveGroupCalls) {
                s.getCallback().stop();
            }
            mKnownActiveGroupCalls.clear();
            mContext.unbindService(mServiceConnection);
        } catch (RemoteException e) {
            // Ignore for now
        } finally {
            mService.set(null);
            sIsInitialized.set(false);
            mServiceConnection = null;
            mInternalCallback.stop();
        }
    }

    /**
     * Starts the requested group call, reporting status to the indicated callback.
     * Returns an object used to control that call.
     *
     * May throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * Asynchronous errors through the callback include any of the errors in
     * {@link MbmsErrors.GeneralErrors}.
     *
     * @param tmgi The TMGI, an identifier for the group call you want to join.
     * @param saiList A list of SAIs for the group call that should be negotiated separately with
     *                the carrier.
     * @param frequencyList A lost of frequencies for the group call that should be negotiated
     *                separately with the carrier.
     * @param executor The executor on which you wish to execute callbacks for this stream.
     * @param callback The callback that you want to receive information about the call on.
     * @return An instance of {@link GroupCall} through which the call can be controlled.
     *         May be {@code null} if an error occurred.
     */
    public @Nullable GroupCall startGroupCall(long tmgi, @NonNull List<Integer> saiList,
            @NonNull List<Integer> frequencyList, @NonNull Executor executor,
            @NonNull GroupCallCallback callback) {
        IMbmsGroupCallService groupCallService = mService.get();
        if (groupCallService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }

        InternalGroupCallCallback serviceCallback = new InternalGroupCallCallback(
                callback, executor);

        GroupCall serviceForApp = new GroupCall(mSubscriptionId,
                groupCallService, this, tmgi, serviceCallback);
        mKnownActiveGroupCalls.add(serviceForApp);

        try {
            int returnCode = groupCallService.startGroupCall(
                    mSubscriptionId, tmgi, saiList, frequencyList, serviceCallback);
            if (returnCode == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (returnCode != MbmsErrors.SUCCESS) {
                mInternalCallback.onError(returnCode, null);
                return null;
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService.set(null);
            sIsInitialized.set(false);
            mInternalCallback.onError(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
            return null;
        }

        return serviceForApp;
    }

    /** @hide */
    public void onGroupCallStopped(GroupCall service) {
        mKnownActiveGroupCalls.remove(service);
    }

    private int bindAndInitialize() {
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                IMbmsGroupCallService groupCallService =
                        IMbmsGroupCallService.Stub.asInterface(service);
                int result;
                try {
                    result = groupCallService.initialize(mInternalCallback,
                            mSubscriptionId);
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Service died before initialization");
                    mInternalCallback.onError(
                            MbmsErrors.InitializationErrors.ERROR_UNABLE_TO_INITIALIZE,
                            e.toString());
                    sIsInitialized.set(false);
                    return;
                } catch (RuntimeException e) {
                    Log.e(LOG_TAG, "Runtime exception during initialization");
                    mInternalCallback.onError(
                            MbmsErrors.InitializationErrors.ERROR_UNABLE_TO_INITIALIZE,
                            e.toString());
                    sIsInitialized.set(false);
                    return;
                }
                if (result == MbmsErrors.UNKNOWN) {
                    // Unbind and throw an obvious error
                    close();
                    throw new IllegalStateException("Middleware must not return"
                            + " an unknown error code");
                }
                if (result != MbmsErrors.SUCCESS) {
                    mInternalCallback.onError(result,
                            "Error returned during initialization");
                    sIsInitialized.set(false);
                    return;
                }
                try {
                    groupCallService.asBinder().linkToDeath(mDeathRecipient, 0);
                } catch (RemoteException e) {
                    mInternalCallback.onError(MbmsErrors.ERROR_MIDDLEWARE_LOST,
                            "Middleware lost during initialization");
                    sIsInitialized.set(false);
                    return;
                }
                mService.set(groupCallService);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                sIsInitialized.set(false);
                mService.set(null);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Log.w(LOG_TAG, "bindAndInitialize: Remote service returned null");
                mInternalCallback.onError(MbmsErrors.ERROR_MIDDLEWARE_LOST,
                        "Middleware service binding returned null");
                sIsInitialized.set(false);
                mService.set(null);
                mContext.unbindService(this);
            }
        };
        return MbmsUtils.startBinding(mContext, MBMS_GROUP_CALL_SERVICE_ACTION, mServiceConnection);
    }
}
