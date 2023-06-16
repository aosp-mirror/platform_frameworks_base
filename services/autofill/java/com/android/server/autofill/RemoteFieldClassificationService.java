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

package com.android.server.autofill;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.assist.classification.FieldClassificationRequest;
import android.service.assist.classification.FieldClassificationResponse;
import android.service.assist.classification.FieldClassificationService;
import android.service.assist.classification.IFieldClassificationCallback;
import android.service.assist.classification.IFieldClassificationService;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.infra.ServiceConnector;

import java.lang.ref.WeakReference;

/**
 * Class responsible for connection with the Remote {@link FieldClassificationService}.
 * This class is instantiated when {@link AutofillManagerServiceImpl} is established.
 * The connection is supposed to be bounded forever, as such, this class persists beyond
 * Autofill {@link Session}'s lifecycle. As such, it can't contain information relevant to Session.
 * This design is completely different from {@link RemoteFillService}.
 */
final class RemoteFieldClassificationService
        extends ServiceConnector.Impl<IFieldClassificationService> {

    private static final String TAG =
            "Autofill" + RemoteFieldClassificationService.class.getSimpleName();

    // Bind forever.
    private static final long TIMEOUT_IDLE_UNBIND_MS =
            AbstractRemoteService.PERMANENT_BOUND_TIMEOUT_MS;
    private final ComponentName mComponentName;

    public interface FieldClassificationServiceCallbacks {
        void onClassificationRequestSuccess(@NonNull FieldClassificationResponse response);
        void onClassificationRequestFailure(int requestId, @Nullable CharSequence message);
        void onClassificationRequestTimeout(int requestId);
        void onServiceDied(@NonNull RemoteFieldClassificationService service);
    }

    RemoteFieldClassificationService(Context context, ComponentName serviceName,
            int serviceUid, int userId) {
        super(context,
                // TODO(b/266379948): Update service
                new Intent(FieldClassificationService.SERVICE_INTERFACE).setComponent(serviceName),
                /* bindingFlags= */ 0, userId, IFieldClassificationService.Stub::asInterface);
        mComponentName = serviceName;
        if (sDebug) {
            Slog.d(TAG, "About to connect to serviceName: " + serviceName);
        }
        // Bind right away.
        connect();
    }

    @Nullable
    static Pair<ServiceInfo, ComponentName> getComponentName(@NonNull String serviceName,
            @UserIdInt int userId, boolean isTemporary) {
        int flags = PackageManager.GET_META_DATA;
        if (!isTemporary) {
            flags |= PackageManager.MATCH_SYSTEM_ONLY;
        }

        final ComponentName serviceComponent;
        ServiceInfo serviceInfo = null;
        try {
            serviceComponent = ComponentName.unflattenFromString(serviceName);
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, flags,
                    userId);
            if (serviceInfo == null) {
                Slog.e(TAG, "Bad service name for flags " + flags + ": " + serviceName);
                return null;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error getting service info for '" + serviceName + "': " + e);
            return null;
        }
        return new Pair<>(serviceInfo, serviceComponent);
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    @Override // from ServiceConnector.Impl
    protected void onServiceConnectionStatusChanged(IFieldClassificationService service,
            boolean connected) {
        try {
            if (connected) {
                service.onConnected(false, false);
            } else {
                service.onDisconnected();
            }
        } catch (Exception e) {
            Slog.w(TAG,
                    "Exception calling onServiceConnectionStatusChanged(" + connected + "): ", e);
        }
    }

    @Override // from AbstractRemoteService
    protected long getAutoDisconnectTimeoutMs() {
        return TIMEOUT_IDLE_UNBIND_MS;
    }

    public void onFieldClassificationRequest(@NonNull FieldClassificationRequest request,
            WeakReference<FieldClassificationServiceCallbacks>
                fieldClassificationServiceCallbacksWeakRef) {
        final long startTime = SystemClock.elapsedRealtime();
        if (sVerbose) {
            Slog.v(TAG, "onFieldClassificationRequest request:" + request);
        }

        run(
                (s) ->
                        s.onFieldClassificationRequest(
                                request,
                                new IFieldClassificationCallback.Stub() {
                                    @Override
                                    public void onCancellable(ICancellationSignal cancellation) {
                                        logLatency(startTime);
                                        if (sDebug) {
                                            Log.d(TAG, "onCancellable");
                                        }
                                    }

                                    @Override
                                    public void onSuccess(FieldClassificationResponse response) {
                                        logLatency(startTime);
                                        if (sDebug) {
                                            if (Build.IS_DEBUGGABLE) {
                                                Slog.d(TAG, "onSuccess Response: " + response);
                                            } else {
                                                String msg = "";
                                                if (response == null
                                                        || response.getClassifications() == null) {
                                                    msg = "null response";
                                                } else {
                                                    msg = "size: "
                                                            + response.getClassifications().size();
                                                }
                                                Slog.d(TAG, "onSuccess " + msg);
                                            }
                                        }
                                        FieldClassificationServiceCallbacks
                                                fieldClassificationServiceCallbacks =
                                                        Helper.weakDeref(
                                                                fieldClassificationServiceCallbacksWeakRef,
                                                                TAG, "onSuccess "
                                                        );
                                        if (fieldClassificationServiceCallbacks == null) {
                                            return;
                                        }
                                        fieldClassificationServiceCallbacks
                                                .onClassificationRequestSuccess(response);
                                    }

                                    @Override
                                    public void onFailure() {
                                        logLatency(startTime);
                                        if (sDebug) {
                                            Slog.d(TAG, "onFailure");
                                        }
                                        FieldClassificationServiceCallbacks
                                                fieldClassificationServiceCallbacks =
                                                        Helper.weakDeref(
                                                                fieldClassificationServiceCallbacksWeakRef,
                                                                TAG, "onFailure "
                                                        );
                                        if (fieldClassificationServiceCallbacks == null) {
                                            return;
                                        }
                                        fieldClassificationServiceCallbacks
                                                .onClassificationRequestFailure(0, null);
                                    }

                                    @Override
                                    public boolean isCompleted() throws RemoteException {
                                        return false;
                                    }

                                    @Override
                                    public void cancel() throws RemoteException {}
                                }));
    }

    private void logLatency(long startTime) {
        final FieldClassificationEventLogger logger = FieldClassificationEventLogger.createLogger();
        logger.startNewLogForRequest();
        logger.maybeSetLatencyMillis(
                SystemClock.elapsedRealtime() - startTime);
        logger.logAndEndEvent();
    }
}
