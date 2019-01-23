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

package com.android.server.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.autofill.augmented.AugmentedAutofillService;
import android.service.autofill.augmented.IAugmentedAutofillService;
import android.service.autofill.augmented.IFillCallback;
import android.text.format.DateUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.infra.AbstractSinglePendingRequestRemoteService;
import com.android.internal.os.IResultReceiver;

final class RemoteAugmentedAutofillService
        extends AbstractSinglePendingRequestRemoteService<RemoteAugmentedAutofillService,
            IAugmentedAutofillService> {

    private static final String TAG = RemoteAugmentedAutofillService.class.getSimpleName();

    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.SECOND_IN_MILLIS;

    RemoteAugmentedAutofillService(Context context, ComponentName serviceName,
            int userId, RemoteAugmentedAutofillServiceCallbacks callbacks,
            boolean bindInstantServiceAllowed, boolean verbose) {
        super(context, AugmentedAutofillService.SERVICE_INTERFACE, serviceName, userId, callbacks,
                bindInstantServiceAllowed, verbose);
    }

    @Nullable
    public static ComponentName getComponentName(@NonNull String componentName,
            @UserIdInt int userId, boolean isTemporary) {
        int flags = PackageManager.GET_META_DATA;
        if (!isTemporary) {
            flags |= PackageManager.MATCH_SYSTEM_ONLY;
        }

        final ComponentName serviceComponent;
        ServiceInfo serviceInfo = null;
        try {
            serviceComponent = ComponentName.unflattenFromString(componentName);
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, flags,
                    userId);
            if (serviceInfo == null) {
                Slog.e(TAG, "Bad service name for flags " + flags + ": " + componentName);
                return null;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error getting service info for '" + componentName + "': " + e);
            return null;
        }
        return serviceComponent;
    }

    @Override // from AbstractRemoteService
    protected IAugmentedAutofillService getServiceInterface(IBinder service) {
        return IAugmentedAutofillService.Stub.asInterface(service);
    }

    @Override // from AbstractRemoteService
    protected long getTimeoutIdleBindMillis() {
        return PERMANENT_BOUND_TIMEOUT_MS;
    }

    @Override // from AbstractRemoteService
    protected long getRemoteRequestMillis() {
        return TIMEOUT_REMOTE_REQUEST_MILLIS;
    }

    /**
     * Called by {@link Session} to request augmented autofill.
     */
    public void onRequestAutofillLocked(int sessionId, @NonNull IAutoFillManagerClient client,
            int taskId, @NonNull ComponentName activityComponent, @NonNull AutofillId focusedId,
            @Nullable AutofillValue focusedValue) {
        scheduleRequest(new PendingAutofillRequest(this, sessionId, client, taskId,
                activityComponent, focusedId, focusedValue));
    }

    /**
     * Called by {@link Session} when it's time to destroy all augmented autofill requests.
     */
    public void onDestroyAutofillWindowsRequest() {
        scheduleAsyncRequest((s) -> s.onDestroyAllFillWindowsRequest());
    }

    // TODO(b/123100811): inline into PendingAutofillRequest if it doesn't have any other subclass
    private abstract static class MyPendingRequest
            extends PendingRequest<RemoteAugmentedAutofillService, IAugmentedAutofillService> {
        protected final int mSessionId;

        private MyPendingRequest(@NonNull RemoteAugmentedAutofillService service, int sessionId) {
            super(service);
            mSessionId = sessionId;
        }
    }

    private static final class PendingAutofillRequest extends MyPendingRequest {
        private final @NonNull AutofillId mFocusedId;
        private final @Nullable AutofillValue mFocusedValue;
        private final @NonNull IAutoFillManagerClient mClient;
        private final @NonNull ComponentName mActivityComponent;
        private final int mTaskId;
        private final long mRequestTime = SystemClock.elapsedRealtime();
        private final @NonNull IFillCallback mCallback;

        protected PendingAutofillRequest(@NonNull RemoteAugmentedAutofillService service,
                int sessionId, @NonNull IAutoFillManagerClient client, int taskId,
                @NonNull ComponentName activityComponent, @NonNull AutofillId focusedId,
                @Nullable AutofillValue focusedValue) {
            super(service, sessionId);
            mClient = client;
            mTaskId = taskId;
            mActivityComponent = activityComponent;
            mFocusedId = focusedId;
            mFocusedValue = focusedValue;
            mCallback = new IFillCallback.Stub() {
                @Override
                public void onSuccess() {
                    if (!finish()) return;
                    // NOTE: so far we don't need notify RemoteAugmentedAutofillServiceCallbacks
                }
            };
        }

        @Override
        public void run() {
            final RemoteAugmentedAutofillService remoteService = getService();
            if (remoteService == null) return;

            final IResultReceiver receiver = new IResultReceiver.Stub() {

                @Override
                public void send(int resultCode, Bundle resultData) throws RemoteException {
                    final IBinder realClient = resultData
                            .getBinder(AutofillManager.EXTRA_AUGMENTED_AUTOFILL_CLIENT);
                    remoteService.mService.onFillRequest(mSessionId, realClient, mTaskId,
                            mActivityComponent, mFocusedId, mFocusedValue, mRequestTime, mCallback);
                }
            };

            // TODO(b/122728762): set cancellation signal, timeout (from both mClient and service),
            // cache IAugmentedAutofillManagerClient reference, etc...
            try {
                mClient.getAugmentedAutofillClient(receiver);
            } catch (RemoteException e) {
                Slog.e(TAG, "exception handling getAugmentedAutofillClient() for "
                        + mSessionId + ": " + e);
                finish();
            }
        }

        @Override
        protected void onTimeout(RemoteAugmentedAutofillService remoteService) {
            Slog.wtf(TAG, "timed out: " + this);
            // NOTE: so far we don't need notify RemoteAugmentedAutofillServiceCallbacks
            finish();
        }

    }

    public interface RemoteAugmentedAutofillServiceCallbacks
            extends VultureCallback<RemoteAugmentedAutofillService> {
        // NOTE: so far we don't need to notify the callback implementation (an inner class on
        // AutofillManagerServiceImpl) of the request results (success, timeouts, etc..), so this
        // callback interface is empty.
    }
}
