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

package com.android.server.ambientcontext;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.app.ambientcontext.AmbientContextEventResponse;
import android.app.ambientcontext.AmbientContextManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.service.ambientcontext.AmbientContextDetectionService;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.infra.AbstractPerUserSystemService;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-user manager service for {@link AmbientContextEvent}s.
 */
final class AmbientContextManagerPerUserService extends
        AbstractPerUserSystemService<AmbientContextManagerPerUserService,
                AmbientContextManagerService> {
    private static final String TAG = AmbientContextManagerPerUserService.class.getSimpleName();

    @Nullable
    @VisibleForTesting
    RemoteAmbientContextDetectionService mRemoteService;

    private ComponentName mComponentName;
    private Context mContext;
    private Set<PendingIntent> mExistingPendingIntents;

    AmbientContextManagerPerUserService(
            @NonNull AmbientContextManagerService master, Object lock, @UserIdInt int userId) {
        super(master, lock, userId);
        mContext = master.getContext();
        mExistingPendingIntents = new HashSet<>();
    }

    void destroyLocked() {
        if (isVerbose()) {
            Slog.v(TAG, "destroyLocked()");
        }

        Slog.d(TAG, "Trying to cancel the remote request. Reason: Service destroyed.");
        if (mRemoteService != null) {
            synchronized (mLock) {
                mRemoteService.unbind();
                mRemoteService = null;
            }
        }
    }

    @GuardedBy("mLock")
    private void ensureRemoteServiceInitiated() {
        if (mRemoteService == null) {
            mRemoteService = new RemoteAmbientContextDetectionService(
                    getContext(), mComponentName, getUserId());
        }
    }

    /**
     * get the currently bound component name.
     */
    @VisibleForTesting
    ComponentName getComponentName() {
        return mComponentName;
    }


    /**
     * Resolves and sets up the service if it had not been done yet. Returns true if the service
     * is available.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    boolean setUpServiceIfNeeded() {
        if (mComponentName == null) {
            mComponentName = updateServiceInfoLocked();
        }
        return mComponentName != null;
    }

    @Override
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    0, mUserId);
            if (serviceInfo != null) {
                final String permission = serviceInfo.permission;
                if (!Manifest.permission.BIND_AMBIENT_CONTEXT_DETECTION_SERVICE.equals(
                        permission)) {
                    throw new SecurityException(String.format(
                            "Service %s requires %s permission. Found %s permission",
                            serviceInfo.getComponentName(),
                            Manifest.permission.BIND_AMBIENT_CONTEXT_DETECTION_SERVICE,
                            serviceInfo.permission));
                }
            }
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        return serviceInfo;
    }

    @Override
    protected void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        synchronized (super.mLock) {
            super.dumpLocked(prefix, pw);
        }
        if (mRemoteService != null) {
            mRemoteService.dump("", new IndentingPrintWriter(pw, "  "));
        }
    }

    /**
     * Handles client registering as an observer. Only one registration is supported per app
     * package. A new registration from the same package will overwrite the previous registration.
     */
    public void onRegisterObserver(AmbientContextEventRequest request,
            PendingIntent pendingIntent) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Service is not available at this moment.");
                sendStatusUpdateIntent(
                        pendingIntent, AmbientContextEventResponse.STATUS_SERVICE_UNAVAILABLE);
                return;
            }

            // Remove any existing intent and unregister for this package before adding a new one.
            String callingPackage = pendingIntent.getCreatorPackage();
            PendingIntent duplicatePendingIntent = findExistingRequestByPackage(callingPackage);
            if (duplicatePendingIntent != null) {
                Slog.d(TAG, "Unregister duplicate request from " + callingPackage);
                onUnregisterObserver(callingPackage);
                mExistingPendingIntents.remove(duplicatePendingIntent);
            }

            // Register new package and add request to mExistingRequests
            startDetection(request, callingPackage, createRemoteCallback());
            mExistingPendingIntents.add(pendingIntent);
        }
    }

    @VisibleForTesting
    void startDetection(AmbientContextEventRequest request, String callingPackage,
            RemoteCallback callback) {
        Slog.d(TAG, "Requested detection of " + request.getEventTypes());
        synchronized (mLock) {
            ensureRemoteServiceInitiated();
            mRemoteService.startDetection(request, callingPackage, callback);
        }
    }

    /**
     * Sends an intent with a status code and empty events.
     */
    void sendStatusUpdateIntent(PendingIntent pendingIntent, int statusCode) {
        AmbientContextEventResponse response = new AmbientContextEventResponse.Builder()
                .setStatusCode(statusCode)
                .build();
        sendResponseIntent(pendingIntent, response);
    }

    /**
     * Unregisters the client from all previously registered events by removing from the
     * mExistingRequests map, and unregister events from the service if those events are not
     * requested by other apps.
     */
    public void onUnregisterObserver(String callingPackage) {
        synchronized (mLock) {
            PendingIntent pendingIntent = findExistingRequestByPackage(callingPackage);
            if (pendingIntent == null) {
                Slog.d(TAG, "No registration found for " + callingPackage);
                return;
            }

            // Remove from existing requests
            mExistingPendingIntents.remove(pendingIntent);
            stopDetection(pendingIntent.getCreatorPackage());
        }
    }

    @VisibleForTesting
    void stopDetection(String packageName) {
        Slog.d(TAG, "Stop detection for " + packageName);
        synchronized (mLock) {
            ensureRemoteServiceInitiated();
            mRemoteService.stopDetection(packageName);
        }
    }

    @Nullable
    private PendingIntent findExistingRequestByPackage(String callingPackage) {
        for (PendingIntent pendingIntent : mExistingPendingIntents) {
            if (pendingIntent.getCreatorPackage().equals(callingPackage)) {
                return pendingIntent;
            }
        }
        return null;
    }

    /**
     * Sends out the Intent to the client after the event is detected.
     *
     * @param pendingIntent Client's PendingIntent for callback
     * @param response Response with status code and detection result
     */
    private void sendResponseIntent(PendingIntent pendingIntent,
            AmbientContextEventResponse response) {
        Intent intent = new Intent();
        intent.putExtra(AmbientContextManager.EXTRA_AMBIENT_CONTEXT_EVENT_RESPONSE, response);
        // Explicitly disallow the receiver from starting activities, to prevent apps from utilizing
        // the PendingIntent as a backdoor to do this.
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setPendingIntentBackgroundActivityLaunchAllowed(false);
        try {
            pendingIntent.send(getContext(), 0, intent, null, null, null,
                    options.toBundle());
            Slog.i(TAG, "Sending PendingIntent to " + pendingIntent.getCreatorPackage() + ": "
                    + response);
        } catch (PendingIntent.CanceledException e) {
            Slog.w(TAG, "Couldn't deliver pendingIntent:" + pendingIntent);
        }
    }

    @NonNull
    private RemoteCallback createRemoteCallback() {
        return new RemoteCallback(result -> {
            AmbientContextEventResponse response = (AmbientContextEventResponse) result.get(
                            AmbientContextDetectionService.RESPONSE_BUNDLE_KEY);
            final long token = Binder.clearCallingIdentity();
            try {
                Set<PendingIntent> pendingIntentForFailedRequests = new HashSet<>();
                for (PendingIntent pendingIntent : mExistingPendingIntents) {
                    // Send PendingIntent if a requesting package matches the response packages.
                    if (response.getPackageName().equals(pendingIntent.getCreatorPackage())) {
                        sendResponseIntent(pendingIntent, response);

                        int statusCode = response.getStatusCode();
                        if (statusCode != AmbientContextEventResponse.STATUS_SUCCESS) {
                            pendingIntentForFailedRequests.add(pendingIntent);
                        }
                        Slog.i(TAG, "Got response of " + response.getEvents() + " for "
                                + pendingIntent.getCreatorPackage() + ". Status: " + statusCode);
                    }
                }

                // Removes the failed requests from the existing requests.
                for (PendingIntent pendingIntent : pendingIntentForFailedRequests) {
                    mExistingPendingIntents.remove(pendingIntent);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        });
    }
}
