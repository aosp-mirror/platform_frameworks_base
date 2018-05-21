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

import static android.service.autofill.FillRequest.INVALID_REQUEST_ID;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.autofill.AutofillService;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.IAutoFillService;
import android.service.autofill.IFillCallback;
import android.service.autofill.ISaveCallback;
import android.service.autofill.SaveRequest;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
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
    // How long after the last interaction with the service we would unbind
    private static final long TIMEOUT_IDLE_BIND_MILLIS = 5 * DateUtils.SECOND_IN_MILLIS;

    // How long after we make a remote request to a fill service we timeout
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 5 * DateUtils.SECOND_IN_MILLIS;

    private static final int MSG_UNBIND = 3;

    private final Context mContext;

    private final ComponentName mComponentName;

    private final Intent mIntent;

    private final FillServiceCallbacks mCallbacks;

    private final int mUserId;

    private final ServiceConnection mServiceConnection = new RemoteServiceConnection();

    private final Handler mHandler;

    private final boolean mBindInstantServiceAllowed;

    private IAutoFillService mAutoFillService;

    private boolean mBinding;

    private boolean mDestroyed;

    private boolean mServiceDied;

    private boolean mCompleted;

    private PendingRequest mPendingRequest;

    public interface FillServiceCallbacks {
        void onFillRequestSuccess(int requestId, @Nullable FillResponse response,
                @NonNull String servicePackageName, int requestFlags);
        void onFillRequestFailure(int requestId, @Nullable CharSequence message,
                @NonNull String servicePackageName);
        void onFillRequestTimeout(int requestId, @NonNull String servicePackageName);
        void onSaveRequestSuccess(@NonNull String servicePackageName,
                @Nullable IntentSender intentSender);
        // TODO(b/80093094): add timeout here too?
        void onSaveRequestFailure(@Nullable CharSequence message,
                @NonNull String servicePackageName);
        void onServiceDied(RemoteFillService service);
    }

    public RemoteFillService(Context context, ComponentName componentName,
            int userId, FillServiceCallbacks callbacks, boolean bindInstantServiceAllowed) {
        mContext = context;
        mCallbacks = callbacks;
        mComponentName = componentName;
        mIntent = new Intent(AutofillService.SERVICE_INTERFACE).setComponent(mComponentName);
        mUserId = userId;
        mHandler = new Handler(FgThread.getHandler().getLooper());
        mBindInstantServiceAllowed = bindInstantServiceAllowed;
    }

    public void destroy() {
        mHandler.sendMessage(obtainMessage(
                RemoteFillService::handleDestroy, this));
    }

    private void handleDestroy() {
        if (checkIfDestroyed()) return;
        if (mPendingRequest != null) {
            mPendingRequest.cancel();
            mPendingRequest = null;
        }
        ensureUnbound();
        mDestroyed = true;
    }

    @Override
    public void binderDied() {
        mHandler.sendMessage(obtainMessage(
                RemoteFillService::handleBinderDied, this));
    }

    private void handleBinderDied() {
        if (checkIfDestroyed()) return;
        if (mAutoFillService != null) {
            mAutoFillService.asBinder().unlinkToDeath(this, 0);
        }
        mAutoFillService = null;
        mServiceDied = true;
        mCallbacks.onServiceDied(this);
    }

    /**
     * Cancel the currently pending request.
     *
     * <p>This can be used when the request is unnecessary or will be superceeded by a request that
     * will soon be queued.
     *
     * @return the id of the canceled request, or {@link FillRequest#INVALID_REQUEST_ID} if no
     *         {@link PendingFillRequest} was canceled.
     */
    public int cancelCurrentRequest() {
        if (mDestroyed) {
            return INVALID_REQUEST_ID;
        }

        int requestId = INVALID_REQUEST_ID;
        if (mPendingRequest != null) {
            if (mPendingRequest instanceof PendingFillRequest) {
                requestId = ((PendingFillRequest) mPendingRequest).mRequest.getId();
            }

            mPendingRequest.cancel();
            mPendingRequest = null;
        }

        return requestId;
    }

    public void onFillRequest(@NonNull FillRequest request) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingFillRequest(request, this));
    }

    public void onSaveRequest(@NonNull SaveRequest request) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingSaveRequest(request, this));
    }

    private void scheduleRequest(PendingRequest pendingRequest) {
        mHandler.sendMessage(obtainMessage(
                RemoteFillService::handlePendingRequest, this, pendingRequest));
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
        pw.append(prefix).append("mBindInstantServiceAllowed=").println(mBindInstantServiceAllowed);
        pw.println();
    }

    private void cancelScheduledUnbind() {
        mHandler.removeMessages(MSG_UNBIND);
    }

    private void scheduleUnbind() {
        cancelScheduledUnbind();
        mHandler.sendMessageDelayed(
                obtainMessage(RemoteFillService::handleUnbind, this)
                        .setWhat(MSG_UNBIND),
                TIMEOUT_IDLE_BIND_MILLIS);
    }

    private void handleUnbind() {
        if (checkIfDestroyed()) return;
        ensureUnbound();
    }

    private void handlePendingRequest(PendingRequest pendingRequest) {
        if (checkIfDestroyed()) return;
        if (mCompleted) {
            return;
        }
        if (!isBound()) {
            if (mPendingRequest != null) {
                mPendingRequest.cancel();
            }
            mPendingRequest = pendingRequest;
            ensureBound();
        } else {
            if (sVerbose) Slog.v(LOG_TAG, "[user: " + mUserId + "] handlePendingRequest()");
            pendingRequest.run();
            if (pendingRequest.isFinal()) {
                mCompleted = true;
            }
        }
    }

    private boolean isBound() {
        return mAutoFillService != null;
    }

    private void ensureBound() {
        if (isBound() || mBinding) {
            return;
        }
        if (sVerbose) Slog.v(LOG_TAG, "[user: " + mUserId + "] ensureBound()");
        mBinding = true;

        int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
        if (mBindInstantServiceAllowed) {
            flags |= Context.BIND_ALLOW_INSTANT;
        }

        final boolean willBind = mContext.bindServiceAsUser(mIntent, mServiceConnection, flags,
                new UserHandle(mUserId));

        if (!willBind) {
            Slog.w(LOG_TAG, "[user: " + mUserId + "] could not bind to " + mIntent + " using flags "
                    + flags);
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
        if (sVerbose) Slog.v(LOG_TAG, "[user: " + mUserId + "] ensureUnbound()");
        mBinding = false;
        if (isBound()) {
            try {
                mAutoFillService.onConnectedStateChanged(false);
            } catch (Exception e) {
                Slog.w(LOG_TAG, "Exception calling onDisconnected(): " + e);
            }
            if (mAutoFillService != null) {
                mAutoFillService.asBinder().unlinkToDeath(this, 0);
                mAutoFillService = null;
            }
        }
        mContext.unbindService(mServiceConnection);
    }

    private void dispatchOnFillRequestSuccess(@NonNull PendingFillRequest pendingRequest,
            @Nullable FillResponse response, int requestFlags) {
        mHandler.post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onFillRequestSuccess(pendingRequest.mRequest.getId(), response,
                        mComponentName.getPackageName(), requestFlags);
            }
        });
    }

    private void dispatchOnFillRequestFailure(@NonNull PendingFillRequest pendingRequest,
            @Nullable CharSequence message) {
        mHandler.post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onFillRequestFailure(pendingRequest.mRequest.getId(), message,
                        mComponentName.getPackageName());
            }
        });
    }

    private void dispatchOnFillRequestTimeout(@NonNull PendingFillRequest pendingRequest) {
        mHandler.post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onFillRequestTimeout(pendingRequest.mRequest.getId(),
                        mComponentName.getPackageName());
            }
        });
    }

    private void dispatchOnFillTimeout(@NonNull ICancellationSignal cancellationSignal) {
        mHandler.post(() -> {
            try {
                cancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.w(LOG_TAG, "Error calling cancellation signal: " + e);
            }
        });
    }

    private void dispatchOnSaveRequestSuccess(PendingRequest pendingRequest,
            IntentSender intentSender) {
        mHandler.post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onSaveRequestSuccess(mComponentName.getPackageName(), intentSender);
            }
        });
    }

    private void dispatchOnSaveRequestFailure(PendingRequest pendingRequest,
            @Nullable CharSequence message) {
        mHandler.post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onSaveRequestFailure(message, mComponentName.getPackageName());
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
                // This is abnormal. Unbinding the connection has been requested already.
                Slog.wtf(LOG_TAG, "onServiceConnected was dispatched after unbindService.");
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
            try {
                mAutoFillService.onConnectedStateChanged(true);
            } catch (RemoteException e) {
                Slog.w(LOG_TAG, "Exception calling onConnected(): " + e);
            }

            if (mPendingRequest != null) {
                PendingRequest pendingRequest = mPendingRequest;
                mPendingRequest = null;
                handlePendingRequest(pendingRequest);
            }

            mServiceDied = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinding = true;
            mAutoFillService = null;
        }
    }

    private boolean checkIfDestroyed() {
        if (mDestroyed) {
            if (sVerbose) {
                Slog.v(LOG_TAG, "Not handling operation as service for "
                        + mComponentName + " is already destroyed");
            }
        }
        return mDestroyed;
    }

    private static abstract class PendingRequest implements Runnable {
        protected final Object mLock = new Object();
        private final WeakReference<RemoteFillService> mWeakService;

        private final Runnable mTimeoutTrigger;
        private final Handler mServiceHandler;

        @GuardedBy("mLock")
        private boolean mCancelled;

        @GuardedBy("mLock")
        private boolean mCompleted;

        PendingRequest(RemoteFillService service) {
            mWeakService = new WeakReference<>(service);
            mServiceHandler = service.mHandler;
            mTimeoutTrigger = () -> {
                synchronized (mLock) {
                    if (mCancelled) {
                        return;
                    }
                    mCompleted = true;
                }

                Slog.w(LOG_TAG, getClass().getSimpleName() + " timed out");
                final RemoteFillService remoteService = mWeakService.get();
                if (remoteService != null) {
                    Slog.w(LOG_TAG, getClass().getSimpleName() + " timed out after "
                            + TIMEOUT_REMOTE_REQUEST_MILLIS + " ms");
                    onTimeout(remoteService);
                }
            };
            mServiceHandler.postAtTime(mTimeoutTrigger,
                    SystemClock.uptimeMillis() + TIMEOUT_REMOTE_REQUEST_MILLIS);
        }

        protected RemoteFillService getService() {
            return mWeakService.get();
        }

        /**
         * Sub-classes must call this method when the remote service finishes, i.e., when it
         * called {@code onFill...} or {@code onSave...}.
         *
         * @return {@code false} in the service is already finished, {@code true} otherwise.
         */
        protected final boolean finish() {
            synchronized (mLock) {
                if (mCompleted || mCancelled) {
                    return false;
                }
                mCompleted = true;
            }
            mServiceHandler.removeCallbacks(mTimeoutTrigger);
            return true;
        }

        @GuardedBy("mLock")
        protected boolean isCancelledLocked() {
            return mCancelled;
        }

        /**
         * Cancels the service.
         *
         * @return {@code false} if service is already canceled, {@code true} otherwise.
         */
        boolean cancel() {
            synchronized (mLock) {
                if (mCancelled || mCompleted) {
                    return false;
                }
                mCancelled = true;
            }

            mServiceHandler.removeCallbacks(mTimeoutTrigger);
            return true;
        }

        /**
         * Called by the self-destructure timeout when the AutofilllService didn't reply to the
         * request on time.
         */
        abstract void onTimeout(RemoteFillService remoteService);

        /**
         * @return whether this request leads to a final state where no
         * other requests can be made.
         */
        boolean isFinal() {
            return false;
        }
    }

    private static final class PendingFillRequest extends PendingRequest {
        private final FillRequest mRequest;
        private final IFillCallback mCallback;
        private ICancellationSignal mCancellation;

        public PendingFillRequest(FillRequest request, RemoteFillService service) {
            super(service);
            mRequest = request;

            mCallback = new IFillCallback.Stub() {
                @Override
                public void onCancellable(ICancellationSignal cancellation) {
                    synchronized (mLock) {
                        final boolean cancelled;
                        synchronized (mLock) {
                            mCancellation = cancellation;
                            cancelled = isCancelledLocked();
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
                    if (!finish()) return;

                    final RemoteFillService remoteService = getService();
                    if (remoteService != null) {
                        remoteService.dispatchOnFillRequestSuccess(PendingFillRequest.this,
                                response, request.getFlags());
                    }
                }

                @Override
                public void onFailure(int requestId, CharSequence message) {
                    if (!finish()) return;

                    final RemoteFillService remoteService = getService();
                    if (remoteService != null) {
                        remoteService.dispatchOnFillRequestFailure(PendingFillRequest.this,
                                message);
                    }
                }
            };
        }

        @Override
        void onTimeout(RemoteFillService remoteService) {
            // NOTE: Must make these 2 calls asynchronously, because the cancellation signal is
            // handled by the service, which could block.
            final ICancellationSignal cancellation;
            synchronized (mLock) {
                cancellation = mCancellation;
            }
            if (cancellation != null) {
                remoteService.dispatchOnFillTimeout(cancellation);
            }
            remoteService.dispatchOnFillRequestTimeout(PendingFillRequest.this);
        }

        @Override
        public void run() {
            synchronized (mLock) {
                if (isCancelledLocked()) {
                    if (sDebug) Slog.d(LOG_TAG, "run() called after canceled: " + mRequest);
                    return;
                }
            }
            final RemoteFillService remoteService = getService();
            if (remoteService != null) {
                try {
                    remoteService.mAutoFillService.onFillRequest(mRequest, mCallback);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling on fill request", e);

                    remoteService.dispatchOnFillRequestFailure(PendingFillRequest.this, null);
                }
            }
        }

        @Override
        public boolean cancel() {
            if (!super.cancel()) return false;

            final ICancellationSignal cancellation;
            synchronized (mLock) {
                cancellation = mCancellation;
            }
            if (cancellation != null) {
                try {
                    cancellation.cancel();
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error cancelling a fill request", e);
                }
            }
            return true;
        }
    }

    private static final class PendingSaveRequest extends PendingRequest {
        private final SaveRequest mRequest;
        private final ISaveCallback mCallback;

        public PendingSaveRequest(@NonNull SaveRequest request,
                @NonNull RemoteFillService service) {
            super(service);
            mRequest = request;

            mCallback = new ISaveCallback.Stub() {
                @Override
                public void onSuccess(IntentSender intentSender) {
                    if (!finish()) return;

                    final RemoteFillService remoteService = getService();
                    if (remoteService != null) {
                        remoteService.dispatchOnSaveRequestSuccess(PendingSaveRequest.this,
                                intentSender);
                    }
                }

                @Override
                public void onFailure(CharSequence message) {
                    if (!finish()) return;

                    final RemoteFillService remoteService = getService();
                    if (remoteService != null) {
                        remoteService.dispatchOnSaveRequestFailure(PendingSaveRequest.this,
                                message);
                    }
                }
            };
        }

        @Override
        void onTimeout(RemoteFillService remoteService) {
            remoteService.dispatchOnSaveRequestFailure(PendingSaveRequest.this, null);
        }

        @Override
        public void run() {
            final RemoteFillService remoteService = getService();
            if (remoteService != null) {
                try {
                    remoteService.mAutoFillService.onSaveRequest(mRequest, mCallback);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling on save request", e);

                    remoteService.dispatchOnSaveRequestFailure(PendingSaveRequest.this, null);
                }
            }
        }

        @Override
        public boolean isFinal() {
            return true;
        }
    }
}
