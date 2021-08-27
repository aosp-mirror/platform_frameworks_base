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

import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Handler;
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

import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.os.IResultReceiver;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

final class RemoteFillService extends ServiceConnector.Impl<IAutoFillService> {

    private static final String TAG = "RemoteFillService";

    private static final long TIMEOUT_IDLE_BIND_MILLIS = 5 * DateUtils.SECOND_IN_MILLIS;
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 5 * DateUtils.SECOND_IN_MILLIS;

    private final FillServiceCallbacks mCallbacks;
    private final Object mLock = new Object();
    private CompletableFuture<FillResponse> mPendingFillRequest;
    private int mPendingFillRequestId = INVALID_REQUEST_ID;
    private final ComponentName mComponentName;

    public interface FillServiceCallbacks
            extends AbstractRemoteService.VultureCallback<RemoteFillService> {
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
        super(context, new Intent(AutofillService.SERVICE_INTERFACE).setComponent(componentName),
                Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
                        | (bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0),
                userId, IAutoFillService.Stub::asInterface);
        mCallbacks = callbacks;
        mComponentName = componentName;
    }

    @Override // from ServiceConnector.Impl
    protected void onServiceConnectionStatusChanged(IAutoFillService service, boolean connected) {
        try {
            service.onConnectedStateChanged(connected);
        } catch (Exception e) {
            Slog.w(TAG, "Exception calling onConnectedStateChanged(" + connected + "): " + e);
        }
    }

    private void dispatchCancellationSignal(@Nullable ICancellationSignal signal) {
        if (signal == null) {
            return;
        }
        try {
            signal.cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Error requesting a cancellation", e);
        }
    }

    @Override // from ServiceConnector.Impl
    protected long getAutoDisconnectTimeoutMs() {
        return TIMEOUT_IDLE_BIND_MILLIS;
    }

    @Override // from ServiceConnector.Impl
    public void addLast(Job<IAutoFillService, ?> iAutoFillServiceJob) {
        // Only maintain single request at a time
        cancelPendingJobs();
        super.addLast(iAutoFillServiceJob);
    }

    /**
     * Cancel the currently pending request.
     *
     * <p>This can be used when the request is unnecessary or will be superceeded by a request that
     * will soon be queued.
     *
     * @return the id of the canceled request, or {@link FillRequest#INVALID_REQUEST_ID} if no
     *         {@link FillRequest} was canceled.
     */
    public int cancelCurrentRequest() {
        synchronized (mLock) {
            return mPendingFillRequest != null && mPendingFillRequest.cancel(false)
                    ? mPendingFillRequestId
                    : INVALID_REQUEST_ID;
        }
    }

    public void onFillRequest(@NonNull FillRequest request) {
        AtomicReference<ICancellationSignal> cancellationSink = new AtomicReference<>();
        AtomicReference<CompletableFuture<FillResponse>> futureRef = new AtomicReference<>();

        CompletableFuture<FillResponse> connectThenFillRequest = postAsync(remoteService -> {
            if (sVerbose) {
                Slog.v(TAG, "calling onFillRequest() for id=" + request.getId());
            }

            CompletableFuture<FillResponse> fillRequest = new CompletableFuture<>();
            remoteService.onFillRequest(request, new IFillCallback.Stub() {
                @Override
                public void onCancellable(ICancellationSignal cancellation) {
                    CompletableFuture<FillResponse> future = futureRef.get();
                    if (future != null && future.isCancelled()) {
                        dispatchCancellationSignal(cancellation);
                    } else {
                        cancellationSink.set(cancellation);
                    }
                }

                @Override
                public void onSuccess(FillResponse response) {
                    fillRequest.complete(response);
                }

                @Override
                public void onFailure(int requestId, CharSequence message) {
                    String errorMessage = message == null ? "" : String.valueOf(message);
                    fillRequest.completeExceptionally(
                            new RuntimeException(errorMessage));
                }
            });
            return fillRequest;
        }).orTimeout(TIMEOUT_REMOTE_REQUEST_MILLIS, TimeUnit.MILLISECONDS);
        futureRef.set(connectThenFillRequest);

        synchronized (mLock) {
            mPendingFillRequest = connectThenFillRequest;
            mPendingFillRequestId = request.getId();
        }

        connectThenFillRequest.whenComplete((res, err) -> Handler.getMain().post(() -> {
            synchronized (mLock) {
                mPendingFillRequest = null;
                mPendingFillRequestId = INVALID_REQUEST_ID;
            }
            if (err == null) {
                mCallbacks.onFillRequestSuccess(request.getId(), res,
                        mComponentName.getPackageName(), request.getFlags());
            } else {
                Slog.e(TAG, "Error calling on fill request", err);
                if (err instanceof TimeoutException) {
                    dispatchCancellationSignal(cancellationSink.get());
                    mCallbacks.onFillRequestTimeout(request.getId());
                } else if (err instanceof CancellationException) {
                    dispatchCancellationSignal(cancellationSink.get());
                } else {
                    mCallbacks.onFillRequestFailure(request.getId(), err.getMessage());
                }
            }
        }));
    }

    public void onSaveRequest(@NonNull SaveRequest request) {
        postAsync(service -> {
            if (sVerbose) Slog.v(TAG, "calling onSaveRequest()");

            CompletableFuture<IntentSender> save = new CompletableFuture<>();
            service.onSaveRequest(request, new ISaveCallback.Stub() {
                @Override
                public void onSuccess(IntentSender intentSender) {
                    save.complete(intentSender);
                }

                @Override
                public void onFailure(CharSequence message) {
                    save.completeExceptionally(new RuntimeException(String.valueOf(message)));
                }
            });
            return save;
        }).orTimeout(TIMEOUT_REMOTE_REQUEST_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((res, err) -> Handler.getMain().post(() -> {
                    if (err == null) {
                        mCallbacks.onSaveRequestSuccess(mComponentName.getPackageName(), res);
                    } else {
                        mCallbacks.onSaveRequestFailure(
                                mComponentName.getPackageName(), err.getMessage());
                    }
                }));
    }

    void onSavedPasswordCountRequest(IResultReceiver receiver) {
        run(service -> service.onSavedPasswordCountRequest(receiver));
    }

    public void destroy() {
        unbind();
    }
}
