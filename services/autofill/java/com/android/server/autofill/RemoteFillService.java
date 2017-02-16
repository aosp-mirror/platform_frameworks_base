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
 * limitations under the License.
 */

package com.android.server.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.ICancellationSignal;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.autofill.AutoFillService;
import android.service.autofill.FillResponse;
import android.service.autofill.IAutoFillService;
import android.service.autofill.IFillCallback;
import android.service.autofill.ISaveCallback;
import android.text.format.DateUtils;
import android.util.Slog;
import com.android.internal.os.HandlerCaller;
import com.android.server.FgThread;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;

/**
 * This class represents a remote fill service. It abstracts away the binding
 * and unbinding from the remote implementation.
 *
 * <p>Clients can call methods of this class without worrying about when and
 * how to bind/unbind/timeout. All state of this class is modified on a handler
 * thread.
 */
final class RemoteFillService implements DeathRecipient {
    private static final String LOG_TAG = "RemoteFillService";

    private static final boolean DEBUG = Helper.DEBUG;

    // How long after the last interaction with the service we would unbind
    private static final long TIMEOUT_IDLE_BIND_MILLIS = 5 * DateUtils.MINUTE_IN_MILLIS;

    private final Context mContext;

    private final ComponentName mComponentName;

    private final Intent mIntent;

    private final FillServiceCallbacks mCallbacks;

    private final int mUserId;

    private final ServiceConnection mServiceConnection = new RemoteServiceConnection();

    private final HandlerCaller mHandler;

    private IAutoFillService mAutoFillService;

    private boolean mBinding;

    private boolean mDestroyed;

    private boolean mServiceDied;

    private boolean mCompleted;

    private PendingRequest mPendingRequest;

    public interface FillServiceCallbacks {
        void onFillRequestSuccess(FillResponse response);
        void onFillRequestFailure(CharSequence message);
        void onSaveRequestSuccess();
        void onSaveRequestFailure(CharSequence message);
        void onServiceDied(RemoteFillService service);
    }

    public RemoteFillService(Context context, ComponentName componentName,
            int userId, FillServiceCallbacks callbacks) {
        mContext = context;
        mCallbacks = callbacks;
        mComponentName = componentName;
        mIntent = new Intent(AutoFillService.SERVICE_INTERFACE)
                .setComponent(mComponentName);
        mUserId = userId;
        mHandler = new MyHandler(context);
    }

    public void destroy() {
        mHandler.obtainMessage(MyHandler.MSG_DESTROY).sendToTarget();
    }

    private void handleDestroy() {
        if (mPendingRequest != null) {
            mPendingRequest.cancel();
            mPendingRequest = null;
        }
        ensureUnbound();
        mDestroyed = true;
    }

    @Override
    public void binderDied() {
        mHandler.obtainMessage(MyHandler.MSG_BINDER_DIED).sendToTarget();
    }

    private void handleBinderDied() {
        if (mAutoFillService != null) {
            mAutoFillService.asBinder().unlinkToDeath(this, 0);
        }
        mAutoFillService = null;
        mServiceDied = true;
        mCallbacks.onServiceDied(this);
    }

    public void onFillRequest(@NonNull AssistStructure structure, @Nullable Bundle extras) {
        cancelScheduledUnbind();
        PendingFillRequest request = new PendingFillRequest(structure, extras, this);
        mHandler.obtainMessageO(MyHandler.MSG_ON_PENDING_REQUEST, request).sendToTarget();
    }

    public void onSaveRequest(@NonNull AssistStructure structure, @Nullable Bundle extras) {
        cancelScheduledUnbind();
        PendingSaveRequest request = new PendingSaveRequest(structure, extras, this);
        mHandler.obtainMessageO(MyHandler.MSG_ON_PENDING_REQUEST, request).sendToTarget();
    }

    // Note: we are dumping without a lock held so this is a bit racy but
    // adding a lock to a class that offloads to a handler thread would
    // mean adding a lock adding overhead to normal runtime operation.
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        String tab = "  ";
        pw.append(prefix).append("service:").println();
        pw.append(prefix).append(tab).append("userId=")
                .append(String.valueOf(mUserId)).println();
        pw.append(prefix).append(tab).append("componentName=")
                .append(mComponentName.flattenToString()).println();
        pw.append(prefix).append(tab).append("destroyed=")
                .append(String.valueOf(mDestroyed)).println();
        pw.append(prefix).append(tab).append("bound=")
                .append(String.valueOf(isBound())).println();
        pw.append(prefix).append(tab).append("hasPendingRequest=")
                .append(String.valueOf(mPendingRequest != null)).println();
        pw.println();
    }

    private void cancelScheduledUnbind() {
        mHandler.removeMessages(MyHandler.MSG_UNBIND);
    }

    private void scheduleUnbind() {
        cancelScheduledUnbind();
        Message message = mHandler.obtainMessage(MyHandler.MSG_UNBIND);
        mHandler.sendMessageDelayed(message, TIMEOUT_IDLE_BIND_MILLIS);
    }

    private void handleUnbind() {
        ensureUnbound();
    }

    private void handlePendingRequest(PendingRequest pendingRequest) {
        if (mDestroyed || mCompleted) {
            return;
        }
        if (pendingRequest.isFinal()) {
            mCompleted = true;
        }
        if (!isBound()) {
            if (mPendingRequest != null) {
                mPendingRequest.cancel();
            }
            mPendingRequest = pendingRequest;
            ensureBound();
        } else {
            if (DEBUG) {
                Slog.d(LOG_TAG, "[user: " + mUserId + "] handlePendingRequest()");
            }
            pendingRequest.run();
        }
    }

    private boolean isBound() {
        return mAutoFillService != null;
    }

    private void ensureBound() {
        if (isBound() || mBinding) {
            return;
        }
        if (DEBUG) {
            Slog.d(LOG_TAG, "[user: " + mUserId + "] ensureBound()");
        }
        mBinding = true;

        boolean willBind = mContext.bindServiceAsUser(mIntent, mServiceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                new UserHandle(mUserId));

        if (!willBind) {
            if (DEBUG) {
                Slog.d(LOG_TAG, "[user: " + mUserId + "] could not bind to " + mIntent);
            }
            mBinding = false;

            if (!mServiceDied) {
                handleBinderDied();
            }
        }
    }

    private void ensureUnbound() {
        if (!isBound() && !mBinding) {
            return;
        }
        if (DEBUG) {
            Slog.d(LOG_TAG, "[user: " + mUserId + "] ensureUnbound()");
        }
        mBinding = false;
        if (isBound()) {
            mAutoFillService.asBinder().unlinkToDeath(this, 0);
            mAutoFillService = null;
        }
        mContext.unbindService(mServiceConnection);
    }

    private void dispatchOnFillRequestSuccess(PendingRequest pendingRequest,
            FillResponse response) {
        mHandler.getHandler().post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onFillRequestSuccess(response);
            }
        });
    }

    private void dispatchOnFillRequestFailure(PendingRequest pendingRequest,
            CharSequence message) {
        mHandler.getHandler().post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onFillRequestFailure(message);
            }
        });
    }

    private void dispatchOnSaveRequestSuccess(PendingRequest pendingRequest) {
        mHandler.getHandler().post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onSaveRequestSuccess();
            }
        });
    }

    private void dispatchOnSaveRequestFailure(PendingRequest pendingRequest,
            CharSequence message) {
        mHandler.getHandler().post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onSaveRequestFailure(message);
            }
        });
    }

    private boolean handleResponseCallbackCommon(PendingRequest pendingRequest) {
        if (mDestroyed) {
            return false;
        }
        if (mPendingRequest == pendingRequest) {
            mPendingRequest = null;
        }
        if (mPendingRequest == null) {
            scheduleUnbind();
        }
        return true;
    }

    private class RemoteServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mDestroyed || !mBinding) {
                mContext.unbindService(mServiceConnection);
                return;
            }
            mBinding = false;
            mAutoFillService = IAutoFillService.Stub.asInterface(service);
            try {
                service.linkToDeath(RemoteFillService.this, 0);
            } catch (RemoteException re) {
                handleBinderDied();
                return;
            }

            if (mPendingRequest != null) {
                handlePendingRequest(mPendingRequest);
            }

            mServiceDied = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinding = true;
            mAutoFillService = null;
        }
    }

    private final class MyHandler extends HandlerCaller {
        public static final int MSG_DESTROY = 1;
        public static final int MSG_BINDER_DIED = 2;
        public static final int MSG_UNBIND = 3;
        public static final int MSG_ON_PENDING_REQUEST = 4;

        public MyHandler(Context context) {
            // Cannot use lambda - doesn't compile
            super(context, FgThread.getHandler().getLooper(), new Callback() {
                @Override
                public void executeMessage(Message message) {
                    if (mDestroyed) {
                        Slog.w(LOG_TAG, "Not handling " + message + " as service for "
                                + mComponentName + " is already destroyed");
                        return;
                    }
                    switch (message.what) {
                        case MSG_DESTROY: {
                            handleDestroy();
                        } break;

                        case MSG_BINDER_DIED: {
                            handleBinderDied();
                        } break;

                        case MSG_UNBIND: {
                            handleUnbind();
                        } break;

                        case MSG_ON_PENDING_REQUEST: {
                            handlePendingRequest((PendingRequest) message.obj);
                        } break;
                    }
                }
            }, false);
        }
    }

    private static abstract class PendingRequest implements Runnable {
        void cancel() {

        }

        /**
         * @return whether this request leads to a final state where no
         * other requests can be made.
         */
        boolean isFinal() {
            return false;
        }
    }

    private static final class PendingFillRequest extends PendingRequest {
        private final Object mLock = new Object();
        private final WeakReference<RemoteFillService> mWeakService;
        private AssistStructure mStructure;
        private Bundle mExtras;
        private final IFillCallback mCallback;
        private ICancellationSignal mCancellation;
        private boolean mCancelled;

        public PendingFillRequest(AssistStructure structure,
                Bundle extras, RemoteFillService service) {
            mStructure = structure;
            mExtras = extras;
            mWeakService = new WeakReference<>(service);
            mCallback = new IFillCallback.Stub() {
                @Override
                public void onCancellable(ICancellationSignal cancellation) {
                    synchronized (mLock) {
                        final boolean cancelled;
                        synchronized (mLock) {
                            mCancellation = cancellation;
                            cancelled = mCancelled;
                        }
                        if (cancelled) {
                            try {
                                cancellation.cancel();
                            } catch (RemoteException e) {
                                Slog.e(LOG_TAG, "Error requesting a cancellation", e);
                            }
                        }
                    }
                }

                @Override
                public void onSuccess(FillResponse response) {
                    RemoteFillService remoteService = mWeakService.get();
                    if (remoteService != null) {
                        remoteService.dispatchOnFillRequestSuccess(
                                PendingFillRequest.this, response);
                    }
                }

                @Override
                public void onFailure(CharSequence message) {
                    RemoteFillService remoteService = mWeakService.get();
                    if (remoteService != null) {
                        remoteService.dispatchOnFillRequestFailure(
                                PendingFillRequest.this, message);
                    }
                }
            };
        }

        @Override
        public void run() {
            RemoteFillService remoteService = mWeakService.get();
            if (remoteService != null) {
                try {
                    remoteService.mAutoFillService.onFillRequest(mStructure,
                            mExtras, mCallback);
                    synchronized (mLock) {
                        mStructure = null;
                        mExtras = null;
                    }
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling on fill request", e);
                    cancel();
                }
            }
        }

        @Override
        public void cancel() {
            final ICancellationSignal cancellation;
            synchronized (mLock) {
                if (mCancelled) {
                    return;
                }
                mCancelled = true;
                cancellation = mCancellation;
            }
            if (cancellation == null) {
                return;
            }
            try {
                cancellation.cancel();
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Error cancelling a fill request", e);
            }
        }
    }

    private static final class PendingSaveRequest extends PendingRequest {
        private final Object mLock = new Object();
        private final WeakReference<RemoteFillService> mWeakService;
        private AssistStructure mStructure;
        private Bundle mExtras;
        private final ISaveCallback mCallback;

        public PendingSaveRequest(@NonNull AssistStructure structure,
                @Nullable Bundle extras, @NonNull RemoteFillService service) {
            mStructure = structure;
            mExtras = extras;
            mWeakService = new WeakReference<>(service);
            mCallback = new ISaveCallback.Stub() {
                @Override
                public void onSuccess() {
                    RemoteFillService service = mWeakService.get();
                    if (service != null) {
                        service.dispatchOnSaveRequestSuccess(
                                PendingSaveRequest.this);
                    }
                }

                @Override
                public void onFailure(CharSequence message) {
                    RemoteFillService service = mWeakService.get();
                    if (service != null) {
                        service.dispatchOnSaveRequestFailure(
                                PendingSaveRequest.this, message);
                    }
                }
            };
        }

        @Override
        public void run() {
            RemoteFillService service = mWeakService.get();
            if (service != null) {
                try {
                    service.mAutoFillService.onSaveRequest(mStructure,
                            mExtras, mCallback);
                    synchronized (mLock) {
                        mStructure = null;
                        mExtras = null;
                    }
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling on save request", e);
                }
            }
        }

        @Override
        public boolean isFinal() {
            return true;
        }
    }
}
