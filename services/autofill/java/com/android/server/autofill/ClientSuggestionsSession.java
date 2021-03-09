/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.IFillCallback;
import android.service.autofill.SaveInfo;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.IAutoFillManagerClient;
import android.view.inputmethod.InlineSuggestionsRequest;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains a client suggestions session with the
 * {@link android.view.autofill.AutofillRequestCallback} through the {@link IAutoFillManagerClient}.
 *
 */
final class ClientSuggestionsSession {

    private static final String TAG = "ClientSuggestionsSession";
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 15 * DateUtils.SECOND_IN_MILLIS;

    private final int mSessionId;
    private final IAutoFillManagerClient mClient;
    private final Handler mHandler;
    private final ComponentName mComponentName;

    private final RemoteFillService.FillServiceCallbacks mCallbacks;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private AndroidFuture<FillResponse> mPendingFillRequest;
    @GuardedBy("mLock")
    private int mPendingFillRequestId = INVALID_REQUEST_ID;

    ClientSuggestionsSession(int sessionId, IAutoFillManagerClient client, Handler handler,
            ComponentName componentName, RemoteFillService.FillServiceCallbacks callbacks) {
        mSessionId = sessionId;
        mClient = client;
        mHandler = handler;
        mComponentName = componentName;
        mCallbacks = callbacks;
    }

    void onFillRequest(int requestId, InlineSuggestionsRequest inlineRequest, int flags) {
        final AtomicReference<ICancellationSignal> cancellationSink = new AtomicReference<>();
        final AtomicReference<AndroidFuture<FillResponse>> futureRef = new AtomicReference<>();
        final AndroidFuture<FillResponse> fillRequest = new AndroidFuture<>();

        mHandler.post(() -> {
            if (sVerbose) {
                Slog.v(TAG, "calling onFillRequest() for id=" + requestId);
            }

            try {
                mClient.requestFillFromClient(requestId, inlineRequest,
                        new FillCallbackImpl(fillRequest, futureRef, cancellationSink));
            } catch (RemoteException e) {
                fillRequest.completeExceptionally(e);
            }
        });

        fillRequest.orTimeout(TIMEOUT_REMOTE_REQUEST_MILLIS, TimeUnit.MILLISECONDS);
        futureRef.set(fillRequest);

        synchronized (mLock) {
            mPendingFillRequest = fillRequest;
            mPendingFillRequestId = requestId;
        }

        fillRequest.whenComplete((res, err) -> mHandler.post(() -> {
            synchronized (mLock) {
                mPendingFillRequest = null;
                mPendingFillRequestId = INVALID_REQUEST_ID;
            }
            if (err == null) {
                processAutofillId(res);
                mCallbacks.onFillRequestSuccess(requestId, res,
                        mComponentName.getPackageName(), flags);
            } else {
                Slog.e(TAG, "Error calling on  client fill request", err);
                if (err instanceof TimeoutException) {
                    dispatchCancellationSignal(cancellationSink.get());
                    mCallbacks.onFillRequestTimeout(requestId);
                } else if (err instanceof CancellationException) {
                    dispatchCancellationSignal(cancellationSink.get());
                } else {
                    mCallbacks.onFillRequestFailure(requestId, err.getMessage());
                }
            }
        }));
    }

    /**
     * Gets the application info for the component.
     */
    @Nullable
    static ApplicationInfo getAppInfo(ComponentName comp, @UserIdInt int userId) {
        try {
            ApplicationInfo si = AppGlobals.getPackageManager().getApplicationInfo(
                    comp.getPackageName(),
                    PackageManager.GET_META_DATA,
                    userId);
            if (si != null) {
                return si;
            }
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Gets the user-visible name of the application.
     */
    @Nullable
    @GuardedBy("mLock")
    static CharSequence getAppLabelLocked(Context context, ApplicationInfo appInfo) {
        return appInfo == null ? null : appInfo.loadSafeLabel(
                context.getPackageManager(), 0 /* do not ellipsize */,
                TextUtils.SAFE_STRING_FLAG_FIRST_LINE | TextUtils.SAFE_STRING_FLAG_TRIM);
    }

    /**
     * Gets the user-visible icon of the application.
     */
    @Nullable
    @GuardedBy("mLock")
    static Drawable getAppIconLocked(Context context, ApplicationInfo appInfo) {
        return appInfo == null ? null : appInfo.loadIcon(context.getPackageManager());
    }

    int cancelCurrentRequest() {
        synchronized (mLock) {
            return mPendingFillRequest != null && mPendingFillRequest.cancel(false)
                    ? mPendingFillRequestId
                    : INVALID_REQUEST_ID;
        }
    }

    /**
     * The {@link AutofillId} which the client gets from its view is not contain the session id,
     * but Autofill framework is using the {@link AutofillId} with a session id. So before using
     * those ids in the Autofill framework, applies the current session id.
     *
     * @param res which response need to apply for a session id
     */
    private void processAutofillId(FillResponse res) {
        if (res == null) {
            return;
        }

        final List<Dataset> datasets = res.getDatasets();
        if (datasets != null && !datasets.isEmpty()) {
            for (int i = 0; i < datasets.size(); i++) {
                final Dataset dataset = datasets.get(i);
                if (dataset != null) {
                    applySessionId(dataset.getFieldIds());
                }
            }
        }

        final SaveInfo saveInfo = res.getSaveInfo();
        if (saveInfo != null) {
            applySessionId(saveInfo.getOptionalIds());
            applySessionId(saveInfo.getRequiredIds());
            applySessionId(saveInfo.getSanitizerValues());
            applySessionId(saveInfo.getTriggerId());
        }
    }

    private void applySessionId(List<AutofillId> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        for (int i = 0; i < ids.size(); i++) {
            applySessionId(ids.get(i));
        }
    }

    private void applySessionId(AutofillId[][] ids) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.length; i++) {
            applySessionId(ids[i]);
        }
    }

    private void applySessionId(AutofillId[] ids) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.length; i++) {
            applySessionId(ids[i]);
        }
    }

    private void applySessionId(AutofillId id) {
        if (id == null) {
            return;
        }
        id.setSessionId(mSessionId);
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

    private class FillCallbackImpl extends IFillCallback.Stub {
        final AndroidFuture<FillResponse> mFillRequest;
        final AtomicReference<AndroidFuture<FillResponse>> mFutureRef;
        final AtomicReference<ICancellationSignal> mCancellationSink;

        FillCallbackImpl(AndroidFuture<FillResponse> fillRequest,
                AtomicReference<AndroidFuture<FillResponse>> futureRef,
                AtomicReference<ICancellationSignal> cancellationSink) {
            mFillRequest = fillRequest;
            mFutureRef = futureRef;
            mCancellationSink = cancellationSink;
        }

        @Override
        public void onCancellable(ICancellationSignal cancellation) {
            AndroidFuture<FillResponse> future = mFutureRef.get();
            if (future != null && future.isCancelled()) {
                dispatchCancellationSignal(cancellation);
            } else {
                mCancellationSink.set(cancellation);
            }
        }

        @Override
        public void onSuccess(FillResponse response) {
            mFillRequest.complete(response);
        }

        @Override
        public void onFailure(int requestId, CharSequence message) {
            String errorMessage = message == null ? "" : String.valueOf(message);
            mFillRequest.completeExceptionally(
                    new RuntimeException(errorMessage));
        }
    }
}
