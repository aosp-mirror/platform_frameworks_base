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

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.service.autofill.AutofillService;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.IAutoFillService;
import android.service.autofill.IFillCallback;
import android.service.autofill.ISaveCallback;
import android.service.autofill.SaveRequest;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.internal.infra.AbstractSinglePendingRequestRemoteService;

import java.util.concurrent.CompletableFuture;

final class RemoteFillService
        extends AbstractSinglePendingRequestRemoteService<RemoteFillService, IAutoFillService> {

    private static final long TIMEOUT_IDLE_BIND_MILLIS = 5 * DateUtils.SECOND_IN_MILLIS;
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 5 * DateUtils.SECOND_IN_MILLIS;

    private final FillServiceCallbacks mCallbacks;

    public interface FillServiceCallbacks extends VultureCallback<RemoteFillService> {
        void onFillRequestSuccess(int requestId, @Nullable FillResponse response,
                @NonNull String servicePackageName, int requestFlags);
        void onFillRequestFailure(int requestId, @Nullable CharSequence message);
        void onFillRequestTimeout(int requestId);
        void onSaveRequestSuccess(@NonNull String servicePackageName,
                @Nullable IntentSender intentSender);
        // TODO(b/80093094): add timeout here too?
        void onSaveRequestFailure(@Nullable CharSequence message,
                @NonNull String servicePackageName);
    }

    RemoteFillService(Context context, ComponentName componentName, int userId,
            FillServiceCallbacks callbacks, boolean bindInstantServiceAllowed) {
        super(context, AutofillService.SERVICE_INTERFACE, componentName, userId, callbacks,
                context.getMainThreadHandler(), Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
                | (bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0), sVerbose);
        mCallbacks = callbacks;
    }

    @Override // from AbstractRemoteService
    protected void handleOnConnectedStateChanged(boolean state) {
        if (mService == null) {
            Slog.w(mTag, "onConnectedStateChanged(): null service");
            return;
        }
        try {
            mService.onConnectedStateChanged(state);
        } catch (Exception e) {
            Slog.w(mTag, "Exception calling onConnectedStateChanged(" + state + "): " + e);
        }
    }

    @Override // from AbstractRemoteService
    protected IAutoFillService getServiceInterface(IBinder service) {
        return IAutoFillService.Stub.asInterface(service);
    }

    @Override // from AbstractRemoteService
    protected long getTimeoutIdleBindMillis() {
        return TIMEOUT_IDLE_BIND_MILLIS;
    }

    @Override // from AbstractRemoteService
    protected long getRemoteRequestMillis() {
        return TIMEOUT_REMOTE_REQUEST_MILLIS;
    }

    /**
     * Cancel the currently pending request.
     *
     * <p>This can be used when the request is unnecessary or will be superceeded by a request that
     * will soon be queued.
     *
     * @return the future id of the canceled request, or {@link FillRequest#INVALID_REQUEST_ID} if
     *          no {@link PendingFillRequest} was canceled.
     */
    public CompletableFuture<Integer> cancelCurrentRequest() {
        return CompletableFuture.supplyAsync(() -> {
            if (isDestroyed()) {
                return INVALID_REQUEST_ID;
            }

            BasePendingRequest<RemoteFillService, IAutoFillService> canceledRequest =
                    handleCancelPendingRequest();
            return canceledRequest instanceof PendingFillRequest
                    ? ((PendingFillRequest) canceledRequest).mRequest.getId()
                    : INVALID_REQUEST_ID;
        }, mHandler::post);
    }

    public void onFillRequest(@NonNull FillRequest request) {
        scheduleRequest(new PendingFillRequest(request, this));
    }

    public void onSaveRequest(@NonNull SaveRequest request) {
        scheduleRequest(new PendingSaveRequest(request, this));
    }

    private boolean handleResponseCallbackCommon(
            @NonNull PendingRequest<RemoteFillService, IAutoFillService> pendingRequest) {
        if (isDestroyed()) return false;

        if (mPendingRequest == pendingRequest) {
            mPendingRequest = null;
        }
        return true;
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
                mCallbacks.onFillRequestFailure(pendingRequest.mRequest.getId(), message);
            }
        });
    }

    private void dispatchOnFillRequestTimeout(@NonNull PendingFillRequest pendingRequest) {
        mHandler.post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onFillRequestTimeout(pendingRequest.mRequest.getId());
            }
        });
    }

    private void dispatchOnFillTimeout(@NonNull ICancellationSignal cancellationSignal) {
        mHandler.post(() -> {
            try {
                cancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.w(mTag, "Error calling cancellation signal: " + e);
            }
        });
    }

    private void dispatchOnSaveRequestSuccess(PendingSaveRequest pendingRequest,
            IntentSender intentSender) {
        mHandler.post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onSaveRequestSuccess(mComponentName.getPackageName(), intentSender);
            }
        });
    }

    private void dispatchOnSaveRequestFailure(PendingSaveRequest pendingRequest,
            @Nullable CharSequence message) {
        mHandler.post(() -> {
            if (handleResponseCallbackCommon(pendingRequest)) {
                mCallbacks.onSaveRequestFailure(message, mComponentName.getPackageName());
            }
        });
    }

    private static final class PendingFillRequest
            extends PendingRequest<RemoteFillService, IAutoFillService> {
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
                                Slog.e(mTag, "Error requesting a cancellation", e);
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
        protected void onTimeout(RemoteFillService remoteService) {
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
                    if (sDebug) Slog.d(mTag, "run() called after canceled: " + mRequest);
                    return;
                }
            }
            final RemoteFillService remoteService = getService();
            if (remoteService != null) {
                if (sVerbose) Slog.v(mTag, "calling onFillRequest() for id=" + mRequest.getId());
                try {
                    remoteService.mService.onFillRequest(mRequest, mCallback);
                } catch (RemoteException e) {
                    Slog.e(mTag, "Error calling on fill request", e);

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
                    Slog.e(mTag, "Error cancelling a fill request", e);
                }
            }
            return true;
        }
    }

    private static final class PendingSaveRequest
            extends PendingRequest<RemoteFillService, IAutoFillService> {
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
        protected void onTimeout(RemoteFillService remoteService) {
            remoteService.dispatchOnSaveRequestFailure(PendingSaveRequest.this, null);
        }

        @Override
        public void run() {
            final RemoteFillService remoteService = getService();
            if (remoteService != null) {
                if (sVerbose) Slog.v(mTag, "calling onSaveRequest()");
                try {
                    remoteService.mService.onSaveRequest(mRequest, mCallback);
                } catch (RemoteException e) {
                    Slog.e(mTag, "Error calling on save request", e);

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
